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
import panic.command.Hooks;
import panic.command.Session;
import panic.command.UsageException;
import panic.util.CancelToken;
import panic.util.Json;
import panic.llm.Llm;
import panic.llm.LlmConfig;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.ModelInfo;
import panic.llm.Providers;
import panic.llm.ToolCall;
import panic.llm.ToolCallDelta;
import panic.interact.AskUser;
import panic.planner.Planner;
import panic.tools.Tools;
import panic.tools.Workspace;
import panic.trace.Span;
import panic.trace.Trace;

/**
 * The HTTP API. Like the CLI it owns no feature: everything goes through hooks (chat.ask, llm.config,
 * llm.current, llm.providers, tool.approve, planner.approve, planner.mode, ask_user.ask, trace.get, trace.list) and commands, so it reflects exactly what is
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
  private final Map<String, Pending<Tools.Decision>> pending = new ConcurrentHashMap<>();
  /** Proposed plans waiting for {@code POST /api/plan}, by id. */
  private final Map<String, Pending<Planner.Answer>> plans = new ConcurrentHashMap<>();
  /** The agent's questions waiting for {@code POST /api/answer}, by id, with the request to check answers against. */
  private final Map<String, Pending<AskUser.Answers>> questions = new ConcurrentHashMap<>();
  private final Map<String, AskUser.Request> asked = new ConcurrentHashMap<>();

  private record Pending<T>(String session, CompletableFuture<T> answer) {
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
      case "/api/conversations" -> {
        if (method.equals("DELETE")) only(ex, method, "DELETE", () -> deleteConversation(ex));
        else only(ex, method, "GET", () -> conversations(ex));
      }
      case "/api/permissions" -> only(ex, method, "GET", () -> permissions(ex));
      case "/api/mode" -> only(ex, method, "GET", () -> mode(ex));
      case "/api/workspaces" -> only(ex, method, "GET", () -> workspaces(ex));
      case "/api/trace" -> only(ex, method, "GET", () -> trace(ex));
      case "/api/chat" -> only(ex, method, "POST", () -> chat(ex, signal));
      case "/api/stream" -> only(ex, method, "GET", () -> follow(ex, signal));
      case "/api/stop" -> only(ex, method, "POST", () -> stop(ex));
      case "/api/command" -> only(ex, method, "POST", () -> command(ex, signal));
      case "/api/approve" -> only(ex, method, "POST", () -> approval(ex));
      case "/api/plan" -> only(ex, method, "POST", () -> planAnswer(ex));
      case "/api/answer" -> only(ex, method, "POST", () -> questionAnswer(ex));
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
   * The conversations a page can open (their ids are session ids), newest first: {@code [{id, title,
   * updatedAt}]}. Sub-agents' conversations are not listed (see {@link Conversations#list}).
   */
  private void conversations(HttpExchange ex) throws IOException {
    Optional<Conversations.Listing> listing = Hooks.bail(ctx, Conversations.LIST, new Conversations.ListQuery());
    if (listing.isEmpty()) {
      Http.error(ex, 503, "conversations are not available");
      return;
    }
    ArrayNode out = Json.MAPPER.createArrayNode();
    for (Conversations.Info i : listing.get().conversations()) {
      if (!SESSION.matcher(i.id()).matches()) continue;
      out.addObject().put("id", i.id()).put("title", i.title()).put("updatedAt", i.updatedAt());
    }
    Http.json(ex, 200, out);
  }

  /**
   * Whether the session runs in bypass mode (YOLO: tool calls that would ask the user just run):
   * {@code {bypass, default}}, {@code default} being what the config says for every chat. {@code 503} when nobody
   * answers (no {@code permissions} plugin: then calls follow the fallback policy). Switching goes through
   * {@code POST /api/command} ({@code bypass on|off}).
   */
  private void permissions(HttpExchange ex) throws IOException {
    String session = session(Http.query(ex).get("session"));
    Optional<Tools.Bypass> bypass = Hooks.bail(ctx, Tools.BYPASS, new Tools.BypassQuery(session));
    if (bypass.isEmpty()) {
      Http.error(ex, 503, "permissions are not available");
      return;
    }
    Http.json(ex, 200, Json.object().put("bypass", bypass.get().on()).put("default", bypass.get().byDefault()));
  }

  /**
   * Whether the session is in plan mode (read-only research, then a plan to approve): {@code {plan, default}},
   * {@code default} being what the config says for every chat. {@code 503} when nobody answers (no {@code planner}
   * plugin). Switching goes through {@code POST /api/command} ({@code plan on|off}).
   */
  private void mode(HttpExchange ex) throws IOException {
    String session = session(Http.query(ex).get("session"));
    Optional<Planner.Mode> mode = Hooks.bail(ctx, Planner.MODE, new Planner.ModeQuery(session));
    if (mode.isEmpty()) {
      Http.error(ex, 503, "plan mode is not available");
      return;
    }
    Http.json(ex, 200, Json.object().put("plan", mode.get().on()).put("default", mode.get().byDefault()));
  }

  /**
   * The projects (workspaces) and which chats work in them: {@code {projects: [{path, name, conversations}],
   * hidden: [id]}}; {@code hidden} are the chats of removed projects, which a page does not list. {@code 503} when
   * nobody answers (no {@code workspace} plugin): the page then shows plain chats. Changing a project goes
   * through {@code POST /api/command} ({@code workspace ...}).
   */
  private void workspaces(HttpExchange ex) throws IOException {
    Optional<Workspace.Projects> all = Hooks.bail(ctx, Workspace.LIST, Workspace.Listing.ALL);
    if (all.isEmpty()) {
      Http.error(ex, 503, "workspaces are not available");
      return;
    }
    ObjectNode out = Json.object();
    ArrayNode projects = out.putArray("projects");
    for (Workspace.Project p : all.get().projects()) {
      ObjectNode o = projects.addObject().put("path", p.path().toString()).put("name", p.name());
      ArrayNode ids = o.putArray("conversations");
      p.conversations().stream().filter(id -> SESSION.matcher(id).matches()).forEach(ids::add);
    }
    ArrayNode hidden = out.putArray("hidden");
    all.get().hidden().stream().filter(id -> SESSION.matcher(id).matches()).forEach(hidden::add);
    Http.json(ex, 200, out);
  }

  /**
   * Deletes a conversation with what works for it (its plan and traces go too). {@code 409} while an answer is
   * running in it, {@code 404} when there is nothing to delete.
   */
  private void deleteConversation(HttpExchange ex) throws IOException {
    String session = session(Http.query(ex).get("session"));
    if (runs.containsKey(session)) {
      Http.error(ex, 409, "an answer is running in this conversation; stop it first");
      return;
    }
    Optional<Conversations.Removal> removal = Hooks.bail(ctx, Conversations.REMOVE, new Conversations.RemoveQuery(session));
    if (removal.isEmpty()) {
      Http.error(ex, 503, "conversations are not available");
      return;
    }
    if (!removal.get().deleted()) {
      Http.error(ex, 404, "no such conversation");
      return;
    }
    Http.json(ex, 200, Json.object().put("ok", true));
  }

  /**
   * The stored conversation, as the page shows it: questions, answers with their tool calls, tool results
   * (cut like {@code tool} events, with {@code status} ok/error) and reviewer rejections ({@code role: review}).
   */
  private void history(HttpExchange ex) throws IOException {
    String session = session(Http.query(ex).get("session"));
    Optional<Conversations.History> history = Hooks.bail(ctx, Conversations.HISTORY, new Conversations.HistoryQuery(session));
    if (history.isEmpty()) {
      Http.error(ex, 503, "conversations are not available");
      return;
    }
    ArrayNode out = Json.MAPPER.createArrayNode();
    List<ObjectNode> questions = new java.util.ArrayList<>();
    for (Message m : history.get().messages()) {
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
    List<Span> saved = roots.stream().filter(r -> Boolean.TRUE.equals(r.attrs().get(Trace.SAVED))).toList();
    int next = 0;
    for (int i = questions.size() - 1; i >= 0 && next < saved.size(); i--) {
      String hash = Trace.questionHash(questions.get(i).path("content").asText());
      for (int j = next; j < saved.size(); j++) {
        if (hash.equals(saved.get(j).stringAttr(Trace.QUESTION_HASH))) {
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
              d -> run.publish("tool_delta", toolDelta(d)), traceId,
              d -> run.publish("agent_delta", Json.object().put("parent", d.parentCallId()).put("agent", d.agent())
                  .put("text", d.text())),
              null));
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

  /** A tool step; a sub-agent's carries {@code parent} (the call that runs it) and {@code agent}. */
  private static ObjectNode step(Chat.Step step) {
    ObjectNode o = Json.object().put("id", step.call().id()).put("name", step.call().name())
        .put("arguments", step.call().arguments());
    if (step.nested()) o.put("parent", step.parentCallId()).put("agent", step.agent());
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
   * {@code approve {id, callId, name, arguments, risk, agent?}} ({@code agent} for a sub-agent's call) and waits for {@code POST /api/approve}. For an answer
   * the question is logged with its events, so a page that reloads gets it again; for a command a browser that
   * leaves denies. A cancelled call is thrown. Other sessions get no answer (null).
   */
  Tools.Decision approve(Tools.Approval a) {
    if (!runs.containsKey(a.conversationId()) && !streams.containsKey(a.conversationId())) return null;
    String id = UUID.randomUUID().toString();
    ObjectNode question = Json.object().put("id", id).put("callId", a.call().id()).put("name", a.call().name())
        .put("arguments", a.call().arguments()).put("risk", a.risk().wire());
    if (a.agent() != null) question.put("agent", a.agent());
    return ask(a.conversationId(), a.signal(), id, pending, "approve", question, Tools.Decision.DENY);
  }

  /**
   * Asks the browser of {@code session} (its answer in flight, else its command stream) with SSE {@code event} and
   * waits for its answer to {@code id}, kept in {@code waiting} meanwhile. A browser that leaves a command stream,
   * or no browser at all, answers {@code gone}; a cancelled {@code signal} is thrown.
   */
  private <T> T ask(String session, Signal signal, String id, Map<String, Pending<T>> waiting, String event,
      ObjectNode question, T gone) {
    Run run = runs.get(session);
    SseResponse sse = run == null ? streams.get(session) : null;
    if (run == null && sse == null) return gone; // ended since it was checked
    CompletableFuture<T> answer = new CompletableFuture<>();
    waiting.put(id, new Pending<>(session, answer));
    Disposable cancelled = signal.onCancel(() -> answer.cancel(false));
    Disposable left = sse == null ? () -> { } : sse.gone().onCancel(() -> answer.complete(gone));
    try {
      if (run != null) run.publish(event, question); else sse.send(event, question);
      return answer.get();
    } catch (CancellationException e) {
      throw new CancelledException("approval cancelled");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException("approval interrupted");
    } catch (ExecutionException e) {
      return gone;
    } finally {
      waiting.remove(id);
      left.dispose();
      cancelled.dispose();
    }
  }

  /**
   * Answers {@link Planner#APPROVE} like {@link #approve}: sends SSE {@code plan {id, plan, items, criteria}} and
   * waits for {@code POST /api/plan}. Logged with an answer's events, so a page that reloads shows it again; a
   * browser that leaves a command stream answers nothing. Other sessions get no answer (null).
   */
  Planner.Answer plan(Planner.Proposal p) {
    if (!runs.containsKey(p.conversationId()) && !streams.containsKey(p.conversationId())) return null;
    ObjectNode question = Json.object().put("id", p.id()).put("plan", p.plan());
    ArrayNode items = question.putArray("items");
    p.items().forEach(items::add);
    ArrayNode criteria = question.putArray("criteria");
    p.criteria().forEach(criteria::add);
    return ask(p.conversationId(), p.signal(), p.id(), plans, "plan", question, null); // a browser gone: no answer
  }

  /** {@code {session, id, approve, feedback?}}: the user's answer to a proposed plan. */
  private void planAnswer(HttpExchange ex) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    Pending<Planner.Answer> p = plans.get(body.path("id").asText(""));
    if (p == null || !p.session().equals(session)) {
      Http.error(ex, 404, "no such plan (answered, expired or cancelled)");
      return;
    }
    String feedback = body.path("feedback").asText("");
    if (feedback.length() > MAX_TEXT) throw new Http.BadRequest("feedback too long");
    p.answer().complete(body.path("approve").asBoolean(false) ? Planner.Answer.APPROVED
        : Planner.Answer.rejected(feedback));
    Http.json(ex, 200, Json.object().put("ok", true));
  }

  /**
   * Answers {@link AskUser#ASK} like {@link #approve}: sends SSE {@code question {id, questions: [{question, header,
   * multiSelect, options: [{label, description}]}]}} and waits for {@code POST /api/answer}. Replayed on resume; a
   * browser that leaves a command stream answers nothing. Other sessions get no answer (null).
   */
  AskUser.Answers question(AskUser.Request r) {
    if (!runs.containsKey(r.conversationId()) && !streams.containsKey(r.conversationId())) return null;
    ObjectNode out = Json.object().put("id", r.id());
    ArrayNode qs = out.putArray("questions");
    for (AskUser.Question q : r.questions()) {
      ObjectNode n = qs.addObject().put("question", q.question()).put("header", q.header())
          .put("multiSelect", q.multiSelect());
      ArrayNode opts = n.putArray("options");
      for (AskUser.Option o : q.options()) opts.addObject().put("label", o.label()).put("description", o.description());
    }
    asked.put(r.id(), r);
    try {
      return ask(r.conversationId(), r.signal(), r.id(), questions, "question", out, null);
    } finally {
      asked.remove(r.id());
    }
  }

  /**
   * {@code {session, id, answers: [{selected: [label], other?}]}}: one answer per question, in order. Labels must
   * be the question's options, and a single-select question takes at most one; all empty skips.
   */
  private void questionAnswer(HttpExchange ex) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    String id = body.path("id").asText("");
    Pending<AskUser.Answers> p = questions.get(id);
    AskUser.Request r = asked.get(id);
    if (p == null || r == null || !p.session().equals(session)) {
      Http.error(ex, 404, "no such question (answered, expired or cancelled)");
      return;
    }
    JsonNode answers = body.path("answers");
    if (!answers.isArray() || answers.size() != r.questions().size()) {
      throw new Http.BadRequest("answers: one per question (" + r.questions().size() + ")");
    }
    List<AskUser.Answer> out = new java.util.ArrayList<>();
    for (int i = 0; i < answers.size(); i++) {
      AskUser.Question q = r.questions().get(i);
      List<String> selected = new java.util.ArrayList<>();
      for (JsonNode l : answers.get(i).path("selected")) {
        String label = l.asText();
        if (q.options().stream().noneMatch(o -> o.label().equals(label))) {
          throw new Http.BadRequest("answers[" + i + "]: unknown option '" + label + "'");
        }
        if (!selected.contains(label)) selected.add(label);
      }
      if (!q.multiSelect() && selected.size() > 1) throw new Http.BadRequest("answers[" + i + "]: pick one option");
      String other = answers.get(i).path("other").asText("");
      if (other.length() > MAX_TEXT) throw new Http.BadRequest("answers[" + i + "]: text too long");
      out.add(new AskUser.Answer(selected, other));
    }
    p.answer().complete(new AskUser.Answers(out));
    Http.json(ex, 200, Json.object().put("ok", true));
  }

  private void approval(HttpExchange ex) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    Pending<Tools.Decision> p = pending.get(body.path("id").asText(""));
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
      if (!Commands.run(ctx, Llm.PROVIDER_COMMAND, id, quiet)) throw new Http.BadRequest("switching provider is not available");
    }
    if (body.hasNonNull("model")) {
      String model = body.get("model").asText().strip();
      if (model.isEmpty() || indexOfWhitespace(model) >= 0) throw new Http.BadRequest("bad model id");
      if (!Commands.run(ctx, Llm.MODEL_COMMAND, model, quiet)) throw new Http.BadRequest("switching model is not available");
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
