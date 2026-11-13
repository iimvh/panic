package panic.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import context.Context;
import context.Task;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import panic.chat.Chat;
import panic.chat.Conversations;
import panic.command.Command;
import panic.command.Commands;
import panic.command.Session;
import panic.command.UsageException;
import panic.llm.CancelToken;
import panic.llm.Json;
import panic.llm.Llm;
import panic.llm.LlmConfig;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.ModelInfo;
import panic.llm.Providers;
import panic.llm.ToolCall;
import panic.llm.ToolCallDelta;
import panic.tools.Tools;
import panic.trace.Span;
import panic.trace.Trace;

/**
 * The HTTP API. Like the CLI it owns no feature: everything goes through hooks (chat.ask, llm.config,
 * llm.current, llm.providers, tool.approve, trace.get, trace.list) and commands, so it reflects exactly what is
 * running.
 */
final class Api {

  private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,100}");
  private static final int MAX_TEXT = 100_000;
  /** A tool result shown to the browser is cut here; the model still gets all of it. */
  private static final int MAX_STEP_CONTENT = 2_000;

  private final Context ctx;
  private final Duration heartbeat;
  private final Duration detach;
  /** The answer in flight of each session: where its events and approval questions go. */
  private final Map<String, Run> runs = new ConcurrentHashMap<>();
  /** The open command stream of each session: where approval questions of commands go. */
  private final Map<String, SseResponse> streams = new ConcurrentHashMap<>();
  /** Approval questions waiting for {@code POST /api/approve}, by id. */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  private record Pending(String session, CompletableFuture<Tools.Decision> answer) {
  }

  Api(Context ctx, Duration heartbeat, Duration detach) {
    this.ctx = ctx;
    this.heartbeat = heartbeat;
    this.detach = detach;
  }

  /** Returns false when the path is not an API path. */
  boolean route(HttpExchange ex, String path, Signal signal) throws IOException {
    String method = ex.getRequestMethod();
    switch (path) {
      case "/api/state" -> only(ex, method, "GET", () -> Http.json(ex, 200, state()));
      case "/api/models" -> only(ex, method, "GET", () -> models(ex, signal));
      case "/api/history" -> only(ex, method, "GET", () -> history(ex));
      case "/api/trace" -> only(ex, method, "GET", () -> trace(ex));
      case "/api/chat" -> only(ex, method, "POST", () -> chat(ex, signal));
      case "/api/stream" -> only(ex, method, "GET", () -> follow(ex, signal));
      case "/api/stop" -> only(ex, method, "POST", () -> stop(ex));
      case "/api/command" -> only(ex, method, "POST", () -> command(ex, signal));
      case "/api/approve" -> only(ex, method, "POST", () -> approval(ex));
      case "/api/settings" -> only(ex, method, "PUT", () -> settings(ex));
      default -> {
        if (!path.startsWith("/api/")) return false;
        Http.error(ex, 404, "no such endpoint");
      }
    }
    return true;
  }

  private interface Handler {
    void run() throws IOException;
  }

  private static void only(HttpExchange ex, String method, String allowed, Handler h) throws IOException {
    if (!method.equals(allowed)) {
      ex.getResponseHeaders().set("Allow", allowed);
      Http.error(ex, 405, "use " + allowed);
      return;
    }
    try {
      h.run();
    } catch (Http.BadRequest | com.fasterxml.jackson.core.JsonProcessingException e) {
      Http.error(ex, 400, e.getMessage());
    }
  }

  // ---------------- state ----------------

  ObjectNode state() {
    ObjectNode out = Json.object();
    Optional<LlmConfig> cfg = ctx.bail(Llm.CONFIG, Llm.Query.CURRENT);
    out.put("provider", cfg.map(LlmConfig::provider).orElse(null));
    out.put("model", cfg.map(LlmConfig::model).orElse(null));
    out.put("connected", ctx.bail(Llm.CURRENT, Llm.Query.CURRENT).isPresent());
    ArrayNode providers = out.putArray("providers");
    ctx.collect(Providers.STATUS, Providers.Query.ALL).stream()
        .sorted(Comparator.comparing(Providers.Status::id))
        .forEach(p -> providers.addObject().put("id", p.id()).put("connected", p.connected()));
    ArrayNode commands = out.putArray("commands");
    for (Command c : Commands.list(ctx)) {
      commands.addObject().put("name", c.name()).put("usage", c.usage()).put("description", c.description());
    }
    return out;
  }

  private void models(HttpExchange ex, Signal signal) throws IOException {
    Optional<Llm> llm = ctx.bail(Llm.CURRENT, Llm.Query.CURRENT);
    if (llm.isEmpty()) {
      Http.error(ex, 409, "no LLM connected");
      return;
    }
    String filter = Http.query(ex).getOrDefault("filter", "").strip().toLowerCase(Locale.ROOT);
    boolean freeOnly = filter.equals("free");
    List<ModelInfo> models;
    try {
      models = llm.get().provider().models(signal);
    } catch (LlmException e) {
      Http.error(ex, 502, e.getMessage());
      return;
    }
    ArrayNode out = Json.MAPPER.createArrayNode();
    for (ModelInfo m : models) {
      if (freeOnly && !Boolean.TRUE.equals(m.free())) continue;
      if (!freeOnly && !filter.isEmpty() && !m.id().toLowerCase(Locale.ROOT).contains(filter)) continue;
      ObjectNode o = out.addObject().put("id", m.id()).put("name", m.name()).put("contextLength", m.contextLength());
      if (m.free() == null) o.putNull("free"); else o.put("free", m.free());
    }
    Http.json(ex, 200, out);
  }

  /**
   * The stored conversation, as the page shows it: questions, answers with their tool calls, tool results
   * (cut like {@code tool} events, with {@code status} ok/error) and reviewer rejections ({@code role: review}).
   */
  private void history(HttpExchange ex) throws IOException {
    String session = session(Http.query(ex).get("session"));
    ArrayNode out = Json.MAPPER.createArrayNode();
    List<ObjectNode> questions = new java.util.ArrayList<>();
    for (Message m : ctx.get(Conversations.KEY).get(session).recent(0)) {
      switch (m.role()) {
        case USER -> {
          if (m.content().startsWith(Chat.REVIEW_FEEDBACK_PREFIX)) {
            String reason = m.content().substring(Chat.REVIEW_FEEDBACK_PREFIX.length());
            int nl = reason.indexOf('\n');
            out.addObject().put("role", "review").put("reason", nl < 0 ? reason : reason.substring(0, nl));
          } else {
            questions.add(out.addObject().put("role", "user").put("content", m.content()));
          }
        }
        case ASSISTANT -> {
          if (m.content().isBlank() && m.toolCalls().isEmpty()) continue;
          ObjectNode o = out.addObject().put("role", "assistant").put("content", m.content());
          ArrayNode calls = o.putArray("toolCalls");
          for (ToolCall c : m.toolCalls()) {
            calls.addObject().put("id", c.id()).put("name", c.name()).put("arguments", c.arguments());
          }
        }
        case TOOL -> {
          boolean error = m.content().startsWith(Chat.TOOL_ERROR_PREFIX);
          String content = error ? m.content().substring(Chat.TOOL_ERROR_PREFIX.length()) : m.content();
          out.addObject().put("role", "tool").put("toolCallId", m.toolCallId())
              .put("status", error ? "error" : "ok").put("content", cut(content));
        }
        default -> { }
      }
    }
    linkTraces(session, questions);
    Http.json(ex, 200, out);
  }

  /**
   * Sets {@code traceId} on the questions whose turn was traced. Both lists are walked newest first and matched
   * on the question's digest: each question takes the next saved trace of the same question. A question with no
   * trace (traced while tracing was off) takes none and leaves the rest in place; traces of turns no longer in
   * the history (compacted, reset) are passed over.
   */
  private void linkTraces(String session, List<ObjectNode> questions) {
    List<Span> roots;
    try {
      roots = ctx.bail(Trace.LIST, new Trace.ListQuery(session, 0)).map(Trace.Roots::roots).orElse(List.of());
    } catch (RuntimeException e) {
      return; // a broken tracer never breaks the history
    }
    List<Span> saved = roots.stream().filter(r -> Boolean.TRUE.equals(r.attrs().get("saved"))).toList();
    int next = 0;
    for (int i = questions.size() - 1; i >= 0 && next < saved.size(); i--) {
      String hash = Chat.questionHash(questions.get(i).path("content").asText());
      for (int j = next; j < saved.size(); j++) {
        if (hash.equals(saved.get(j).stringAttr("questionHash"))) {
          questions.get(i).put("traceId", saved.get(j).traceId());
          next = j + 1;
          break;
        }
      }
    }
  }

  /** The spans of one trace of a session, in the order they started. 404 when unknown or tracing is off. */
  private void trace(HttpExchange ex) throws IOException {
    Map<String, String> q = Http.query(ex);
    String session = session(q.get("session"));
    String id = q.get("id");
    if (id == null || id.isBlank()) throw new Http.BadRequest("missing id");
    Optional<Trace.Spans> spans = ctx.bail(Trace.GET, new Trace.Query(session, id.strip()));
    if (spans.isEmpty()) {
      boolean tracing = ctx.bail(Trace.LIST, new Trace.ListQuery(session, 1)).isPresent();
      Http.error(ex, 404, tracing ? "no such trace" : "tracing is off");
      return;
    }
    ObjectNode out = Json.object().put("traceId", spans.get().spans().get(0).traceId());
    ArrayNode list = out.putArray("spans");
    for (Span s : spans.get().spans()) {
      ObjectNode o = list.addObject().put("id", s.spanId()).put("parentId", s.parentId()).put("name", s.name())
          .put("startMicros", s.startMicros()).put("durationMicros", s.durationMicros())
          .put("status", s.status().name().toLowerCase(Locale.ROOT));
      if (s.error() != null) o.put("error", s.error());
      o.set("attrs", Json.MAPPER.valueToTree(s.attrs()));
    }
    Http.json(ex, 200, out);
  }

  // ---------------- streaming ----------------

  /**
   * Starts an answer and streams it. The answer runs on this request's task, but the connection only
   * watches it: if the browser goes, the answer goes on (see {@link Run}) until {@code POST /api/stop}, until
   * nobody has watched for {@code detachSeconds}, or until the plugin stops. One answer per session at a time.
   */
  private void chat(HttpExchange ex, Signal signal) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    String text = text(body.path("text").asText(""));
    Run run = new Run(detach);
    if (runs.putIfAbsent(session, run) != null) {
      Http.error(ex, 409, "an answer is already running in this session");
      return;
    }
    SseResponse sse = SseResponse.open(ex);
    Task<Void> ping = sse.heartbeat(ctx, heartbeat);
    Task<Void> watch = detach.isZero() ? null : ctx.spawn("detach " + session, s -> {
      while (!s.isCancelled() && !run.abandoned()) Thread.sleep(Math.min(detach.toMillis(), 250));
      run.cancel();
      return null;
    });
    try {
      String traceId = Trace.newId();
      run.publish("start", Json.object().put("question", text).put("startedAt", System.currentTimeMillis())
          .put("traceId", traceId));
      run.attach(sse, 0);
      answer(run, session, text, traceId, CancelToken.any(signal, run.cancelled()));
    } finally {
      runs.remove(session, run);
      run.end();
      if (watch != null) watch.dispose();
      ping.dispose();
    }
  }

  private void answer(Run run, String session, String text, String traceId, Signal signal) {
    try {
      Optional<Chat.Reply> reply = Chat.ask(ctx,
          new Chat.Ask(session, text, signal, d -> run.publish("delta", Json.object().put("text", d)),
              step -> run.publish("tool", step(step)),
              r -> run.publish("review", Json.object().put("attempt", r.attempt()).put("pass", r.verdict().pass())
                  .put("reason", r.verdict().reason())),
              d -> run.publish("tool_delta", toolDelta(d)), traceId));
      if (reply.isEmpty()) {
        run.publish("unavailable", Json.object().put("message", "no LLM connected"));
        return;
      }
      Chat.Reply r = reply.get();
      ObjectNode done = Json.object().put("model", r.model()).put("finishReason", r.finishReason())
          .put("stop", r.stop()).put("toolCalls", r.toolCalls());
      if (r.review() != null) done.put("review", r.review());
      if (r.traceId() != null) done.put("traceId", r.traceId());
      done.putObject("usage").put("promptTokens", r.usage().promptTokens())
          .put("completionTokens", r.usage().completionTokens());
      run.publish("done", done);
    } catch (CancelledException e) {
      run.publish("cancelled", Json.object());
    } catch (RuntimeException e) {
      run.publish("error", Json.object().put("message", message(e)));
    }
  }

  /**
   * Attaches to the answer in flight of a session: replays its events after {@code after} (the last SSE id
   * the page saw, 0 for all), then streams the rest. {@code 204} when the session has none.
   */
  private void follow(HttpExchange ex, Signal signal) throws IOException {
    Map<String, String> q = Http.query(ex);
    String session = session(q.get("session"));
    long after;
    try {
      after = Long.parseLong(q.getOrDefault("after", "0"));
    } catch (NumberFormatException e) {
      throw new Http.BadRequest("bad after");
    }
    Run run = runs.get(session);
    if (run == null) {
      ex.sendResponseHeaders(204, -1);
      return;
    }
    SseResponse sse = SseResponse.open(ex);
    Task<Void> ping = sse.heartbeat(ctx, heartbeat);
    try {
      run.attach(sse, after);
      run.await(sse, signal);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      ping.dispose();
    }
  }

  private void stop(HttpExchange ex) throws IOException {
    String session = session(Http.body(ex).path("session").asText(null));
    Run run = runs.get(session);
    if (run == null) {
      Http.error(ex, 404, "nothing is running in this session");
      return;
    }
    run.cancel();
    Http.json(ex, 200, Json.object().put("ok", true));
  }

  private static ObjectNode step(Chat.Step step) {
    ObjectNode o = Json.object().put("id", step.call().id()).put("name", step.call().name())
        .put("arguments", step.call().arguments());
    if (!step.done()) return o.put("status", "running");
    return o.put("status", step.result().isError() ? "error" : "ok").put("content", cut(step.result().content()));
  }

  private static ObjectNode toolDelta(ToolCallDelta d) {
    ObjectNode o = Json.object().put("index", d.index());
    if (d.id() != null) o.put("id", d.id());
    if (d.name() != null) o.put("name", d.name());
    return o.put("arguments", d.arguments());
  }

  private static String cut(String content) {
    return content.length() > MAX_STEP_CONTENT ? content.substring(0, MAX_STEP_CONTENT) + "…" : content;
  }

  private void command(HttpExchange ex, Signal signal) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    String line = body.path("line").asText("").strip();
    if (line.startsWith("/")) line = line.substring(1);
    if (line.isEmpty()) throw new Http.BadRequest("line must not be empty");
    int space = indexOfWhitespace(line);
    String name = space < 0 ? line : line.substring(0, space);
    String args = space < 0 ? "" : line.substring(space + 1);

    SseResponse sse = SseResponse.open(ex);
    Session s = new Session(session, t -> sse.send("delta", Json.object().put("text", t)),
        CancelToken.any(signal, sse.gone()));
    Task<Void> ping = sse.heartbeat(ctx, heartbeat);
    streams.put(session, sse);
    try {
      if (Commands.run(ctx, name, args, s)) {
        sse.send("done");
      } else {
        sse.send("unknown", Json.object().put("name", name));
      }
    } catch (UsageException e) {
      sse.send("usage", Json.object().put("usage", e.usage()).put("message", e.getMessage()));
    } catch (CancelledException e) {
      sse.send("cancelled");
    } catch (RuntimeException e) {
      sse.send("error", Json.object().put("message", message(e)));
    } finally {
      streams.remove(session, sse);
      ping.dispose();
    }
  }

  // ---------------- approvals ----------------

  /**
   * Answers {@link Tools#APPROVE} for a session with an answer or a command running here: sends SSE
   * {@code approve {id, callId, name, arguments, risk}} and waits for {@code POST /api/approve}. For an answer
   * the question is logged with its events, so a page that reloads gets it again; for a command a browser that
   * leaves denies. A cancelled call is thrown. Other sessions get no answer (null).
   */
  Tools.Decision approve(Tools.Approval a) {
    Run run = runs.get(a.conversationId());
    SseResponse sse = run == null ? streams.get(a.conversationId()) : null;
    if (run == null && sse == null) return null;
    String id = UUID.randomUUID().toString();
    CompletableFuture<Tools.Decision> answer = new CompletableFuture<>();
    pending.put(id, new Pending(a.conversationId(), answer));
    Disposable cancelled = a.signal().onCancel(() -> answer.cancel(false));
    Disposable left = sse == null ? () -> { } : sse.gone().onCancel(() -> answer.complete(Tools.Decision.DENY));
    try {
      ObjectNode question = Json.object().put("id", id).put("callId", a.call().id()).put("name", a.call().name())
          .put("arguments", a.call().arguments()).put("risk", a.risk().wire());
      if (run != null) run.publish("approve", question); else sse.send("approve", question);
      return answer.get();
    } catch (CancellationException e) {
      throw new CancelledException("approval cancelled");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException("approval interrupted");
    } catch (ExecutionException e) {
      return Tools.Decision.DENY;
    } finally {
      pending.remove(id);
      left.dispose();
      cancelled.dispose();
    }
  }

  private void approval(HttpExchange ex) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    Pending p = pending.get(body.path("id").asText(""));
    if (p == null || !p.session().equals(session)) {
      Http.error(ex, 404, "no such approval (answered, expired or cancelled)");
      return;
    }
    p.answer().complete(body.path("allow").asBoolean(false) ? Tools.Decision.ALLOW : Tools.Decision.DENY);
    Http.json(ex, 200, Json.object().put("ok", true));
  }

  // ---------------- settings ----------------

  private void settings(HttpExchange ex) throws IOException {
    JsonNode body = Http.body(ex);
    StringBuilder ignored = new StringBuilder();
    Session quiet = new Session("web", ignored::append, new CancelToken());
    if (body.hasNonNull("provider")) {
      String id = body.get("provider").asText().strip();
      boolean known = ctx.collect(Providers.STATUS, Providers.Query.ALL).stream().anyMatch(p -> p.id().equals(id));
      if (!known) throw new Http.BadRequest("unknown provider '" + id + "'");
      if (!Commands.run(ctx, "provider", id, quiet)) throw new Http.BadRequest("switching provider is not available");
    }
    if (body.hasNonNull("model")) {
      String model = body.get("model").asText().strip();
      if (model.isEmpty() || indexOfWhitespace(model) >= 0) throw new Http.BadRequest("bad model id");
      if (!Commands.run(ctx, "model", model, quiet)) throw new Http.BadRequest("switching model is not available");
    }
    Http.json(ex, 200, state());
  }

  // ---------------- input checks ----------------

  private static String session(String s) {
    if (s == null || !SESSION.matcher(s).matches()) throw new Http.BadRequest("bad or missing session");
    return s;
  }

  private static String text(String t) {
    String text = t.strip();
    if (text.isEmpty()) throw new Http.BadRequest("text must not be empty");
    if (text.length() > MAX_TEXT) throw new Http.BadRequest("text too long");
    return text;
  }

  private static String message(RuntimeException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  private static int indexOfWhitespace(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) return i;
    }
    return -1;
  }
}
