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
import panic.llm.Role;
import panic.tools.Tools;

/**
 * The HTTP API. Like the CLI it owns no feature: everything goes through hooks (chat.ask, llm.config,
 * llm.current, llm.providers, tool.approve) and commands, so it reflects exactly what is running.
 */
final class Api {

  private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,100}");
  private static final int MAX_TEXT = 100_000;
  /** A tool result shown to the browser is cut here; the model still gets all of it. */
  private static final int MAX_STEP_CONTENT = 2_000;

  private final Context ctx;
  private final Duration heartbeat;
  /** The open SSE stream of each session: where its approval questions go. */
  private final Map<String, SseResponse> streams = new ConcurrentHashMap<>();
  /** Approval questions waiting for {@code POST /api/approve}, by id. */
  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  private record Pending(String session, CompletableFuture<Tools.Decision> answer) {
  }

  Api(Context ctx, Duration heartbeat) {
    this.ctx = ctx;
    this.heartbeat = heartbeat;
  }

  /** Returns false when the path is not an API path. */
  boolean route(HttpExchange ex, String path, Signal signal) throws IOException {
    String method = ex.getRequestMethod();
    switch (path) {
      case "/api/state" -> only(ex, method, "GET", () -> Http.json(ex, 200, state()));
      case "/api/models" -> only(ex, method, "GET", () -> models(ex, signal));
      case "/api/history" -> only(ex, method, "GET", () -> history(ex));
      case "/api/chat" -> only(ex, method, "POST", () -> chat(ex, signal));
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

  private void history(HttpExchange ex) throws IOException {
    String session = session(Http.query(ex).get("session"));
    ArrayNode out = Json.MAPPER.createArrayNode();
    for (Message m : ctx.get(Conversations.KEY).get(session).recent(0)) {
      // tool traffic is for the model; the page shows the questions and the answers
      if (m.role() == Role.TOOL || m.content().isBlank()) continue;
      out.addObject().put("role", m.role().wire()).put("content", m.content());
    }
    Http.json(ex, 200, out);
  }

  // ---------------- streaming ----------------

  private void chat(HttpExchange ex, Signal signal) throws IOException {
    JsonNode body = Http.body(ex);
    String session = session(body.path("session").asText(null));
    String text = text(body.path("text").asText(""));
    SseResponse sse = SseResponse.open(ex);
    Signal both = CancelToken.any(signal, sse.gone());
    Task<Void> ping = sse.heartbeat(ctx, heartbeat);
    streams.put(session, sse);
    try {
      Optional<Chat.Reply> reply = Chat.ask(ctx,
          new Chat.Ask(session, text, both, d -> sse.send("delta", Json.object().put("text", d)),
              step -> sse.send("tool", step(step)),
              r -> sse.send("review", Json.object().put("attempt", r.attempt()).put("pass", r.verdict().pass())
                  .put("reason", r.verdict().reason()))));
      if (reply.isEmpty()) {
        sse.send("unavailable", Json.object().put("message", "no LLM connected"));
        return;
      }
      Chat.Reply r = reply.get();
      ObjectNode done = Json.object().put("model", r.model()).put("finishReason", r.finishReason())
          .put("stop", r.stop()).put("toolCalls", r.toolCalls());
      if (r.review() != null) done.put("review", r.review());
      done.putObject("usage").put("promptTokens", r.usage().promptTokens())
          .put("completionTokens", r.usage().completionTokens());
      sse.send("done", done);
    } catch (CancelledException e) {
      sse.send("cancelled");
    } catch (RuntimeException e) {
      sse.send("error", Json.object().put("message", message(e)));
    } finally {
      streams.remove(session, sse);
      ping.dispose();
    }
  }

  private static ObjectNode step(Chat.Step step) {
    ObjectNode o = Json.object().put("id", step.call().id()).put("name", step.call().name())
        .put("arguments", step.call().arguments());
    if (!step.done()) return o.put("status", "running");
    String content = step.result().content();
    if (content.length() > MAX_STEP_CONTENT) content = content.substring(0, MAX_STEP_CONTENT) + "…";
    return o.put("status", step.result().isError() ? "error" : "ok").put("content", content);
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
   * Answers {@link Tools#APPROVE} for a session that has a stream open (the chat or command that made the
   * call): sends SSE {@code approve {id, callId, name, arguments, risk}} and waits for {@code POST /api/approve}. A
   * browser that leaves denies; a cancelled call is thrown. Other sessions get no answer (null).
   */
  Tools.Decision approve(Tools.Approval a) {
    SseResponse sse = streams.get(a.conversationId());
    if (sse == null) return null;
    String id = UUID.randomUUID().toString();
    CompletableFuture<Tools.Decision> answer = new CompletableFuture<>();
    pending.put(id, new Pending(a.conversationId(), answer));
    Disposable cancelled = a.signal().onCancel(() -> answer.cancel(false));
    Disposable left = sse.gone().onCancel(() -> answer.complete(Tools.Decision.DENY));
    try {
      sse.send("approve", Json.object().put("id", id).put("callId", a.call().id()).put("name", a.call().name())
          .put("arguments", a.call().arguments()).put("risk", a.risk().wire()));
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
