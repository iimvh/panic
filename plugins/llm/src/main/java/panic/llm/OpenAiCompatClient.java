package panic.llm;

import panic.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A ChatModel speaking the OpenAI chat-completions protocol ({@code POST {baseUrl}/chat/completions}),
 * which OpenRouter, Mistral and most other providers implement. Blocking, cancellable through the Signal.
 *
 * Transient failures (unreachable, 408, 429, 5xx) are retried by the {@link RetryPolicy}, honouring
 * {@code Retry-After}; the waits end as soon as the Signal is cancelled. A stream is retried only until the
 * provider has accepted it: a stream that breaks after deltas were handed out is never replayed.
 */
public final class OpenAiCompatClient implements ChatModel {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
  private static final Set<Integer> RETRYABLE = Set.of(0, 408, 429, 500, 502, 503, 504);

  private final HttpClient http;
  private final String baseUrl;
  private final String apiKey;
  private final String providerName;
  private final RetryPolicy retry;
  private final boolean streamUsage;

  public OpenAiCompatClient(String providerName, String baseUrl, String apiKey) {
    this(providerName, baseUrl, apiKey, RetryPolicy.DEFAULT);
  }

  public OpenAiCompatClient(String providerName, String baseUrl, String apiKey, RetryPolicy retry) {
    this(providerName, baseUrl, apiKey, retry, true);
  }

  public OpenAiCompatClient(String providerName, String baseUrl, String apiKey, RetryPolicy retry,
      boolean streamUsage) {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), providerName, baseUrl, apiKey, retry,
        streamUsage);
  }

  public OpenAiCompatClient(HttpClient http, String providerName, String baseUrl, String apiKey,
      RetryPolicy retry) {
    this(http, providerName, baseUrl, apiKey, retry, true);
  }

  /**
   * {@code streamUsage}: ask for the token usage of a streamed reply ({@code stream_options.include_usage}). Most
   * servers send no usage in a stream without it, which leaves the turn's token budget and counts at zero; turn it
   * off only for a server that refuses the option.
   */
  public OpenAiCompatClient(HttpClient http, String providerName, String baseUrl, String apiKey,
      RetryPolicy retry, boolean streamUsage) {
    this.streamUsage = streamUsage;
    this.http = Objects.requireNonNull(http, "http");
    this.providerName = Objects.requireNonNull(providerName, "providerName");
    this.baseUrl = stripSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey").strip();
    this.retry = Objects.requireNonNull(retry, "retry");
  }

  // ---------------- chat ----------------

  @Override
  public Completion complete(ChatRequest request, Signal signal) {
    JsonNode json = readJson(sendChecked(post("/chat/completions", body(request, false)), signal), signal);
    JsonNode choice = json.path("choices").path(0);
    JsonNode message = choice.path("message");
    List<ToolCall> calls = new ArrayList<>();
    int i = 0;
    for (JsonNode call : message.path("tool_calls")) {
      JsonNode fn = call.path("function");
      calls.add(new ToolCall(call.path("id").asText("call_" + i), fn.path("name").asText(""),
          arguments(fn.path("arguments"))));
      i++;
    }
    return new Completion(
        Content.text(message.path("content")),
        json.path("model").asText(request.model()),
        textOrNull(choice.path("finish_reason")),
        usage(json.path("usage")),
        calls,
        Content.reasoning(message));
  }

  @Override
  public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta) {
    return stream(request, signal, onDelta, d -> { });
  }

  @Override
  public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta,
      Consumer<ToolCallDelta> onToolDelta) {
    return stream(request, signal, onDelta, onToolDelta, r -> { });
  }

  @Override
  public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta,
      Consumer<ToolCallDelta> onToolDelta, Consumer<String> onReasoning) {
    Objects.requireNonNull(onDelta, "onDelta");
    Objects.requireNonNull(onToolDelta, "onToolDelta");
    Objects.requireNonNull(onReasoning, "onReasoning");
    HttpResponse<InputStream> response = sendChecked(post("/chat/completions", body(request, true)), signal);

    ToolCallParts calls = new ToolCallParts();
    StringBuilder text = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    String[] model = {request.model()};
    String[] finish = {null};
    Usage[] usage = {Usage.NONE};
    InputStream in = response.body();
    Disposable abort = signal.onCancel(() -> closeQuietly(in));
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      Sse.read(reader, data -> {
        JsonNode chunk = Json.parseOrNull(data);
        if (chunk == null) return;
        if (chunk.has("error")) throw error(chunk.path("error").path("code").asInt(0), data);
        if (chunk.hasNonNull("model")) model[0] = chunk.get("model").asText();
        if (chunk.path("usage").isObject()) usage[0] = usage(chunk.get("usage"));
        JsonNode choice = chunk.path("choices").path(0);
        String thought = Content.reasoning(choice.path("delta"));
        if (!thought.isEmpty()) {
          reasoning.append(thought);
          onReasoning.accept(thought);
        }
        String piece = Content.text(choice.path("delta").path("content"));
        if (!piece.isEmpty()) {
          text.append(piece);
          onDelta.accept(piece);
        }
        calls.add(choice.path("delta").path("tool_calls"), onToolDelta);
        String reason = textOrNull(choice.path("finish_reason"));
        if (reason != null) finish[0] = reason;
      });
    } catch (IOException e) {
      if (signal.isCancelled()) throw cancelled(e);
      throw new LlmException(providerName + " stream broken: " + e.getMessage(), e);
    } finally {
      abort.dispose();
    }
    signal.throwIfCancelled();
    return new Completion(text.toString(), model[0], finish[0], usage[0], calls.build(), reasoning.toString());
  }

  // ---------------- plain REST ----------------

  /** GET {baseUrl}{path} with the bearer key, as JSON. Throws LlmException on non-2xx. */
  public JsonNode get(String path, Signal signal) {
    HttpRequest request = request(path).GET().build();
    return readJson(sendChecked(request, signal), signal);
  }

  public String baseUrl() {
    return baseUrl;
  }

  // ---------------- internals ----------------

  /** What an assistant message that said nothing is sent as (see {@link #body}). */
  static final String EMPTY_ANSWER = "(empty answer)";

  private ObjectNode body(ChatRequest request, boolean stream) {
    if (request.model() == null || request.model().isBlank()) {
      throw new LlmException(0, providerName + ": no model selected");
    }
    ObjectNode body = Json.object();
    body.put("model", request.model());
    ArrayNode messages = body.putArray("messages");
    for (Message m : request.messages()) {
      ObjectNode out = messages.addObject().put("role", m.role().wire());
      if (m.hasToolCalls() && m.content().isEmpty()) {
        out.putNull("content");
      } else if (m.role() == Role.ASSISTANT && m.content().isBlank()) {
        // a model may end a turn with nothing to say (its text all in reasoning); providers such as Mistral refuse
        // an assistant message without content or tool calls, and dropping it would put a user message after a
        // tool result, which they refuse too
        out.put("content", EMPTY_ANSWER);
      } else {
        out.put("content", m.content());
      }
      if (m.hasToolCalls()) {
        ArrayNode calls = out.putArray("tool_calls");
        for (ToolCall c : m.toolCalls()) {
          ObjectNode call = calls.addObject().put("id", c.id()).put("type", "function");
          call.putObject("function").put("name", c.name()).put("arguments", c.arguments());
        }
      }
      if (m.toolCallId() != null) out.put("tool_call_id", m.toolCallId());
    }
    if (request.temperature() != null) body.put("temperature", request.temperature());
    if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
    if (request.sentReasoningEffort() != null) body.put("reasoning_effort", request.sentReasoningEffort());
    if (!request.tools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      for (ToolSpec t : request.tools()) {
        ObjectNode fn = tools.addObject().put("type", "function").putObject("function");
        fn.put("name", t.name()).put("description", t.description()).set("parameters", t.parameters());
      }
    }
    ToolChoice choice = request.toolChoice();
    if (choice != null) {
      if (choice.name() == null) {
        body.put("tool_choice", choice.mode());
      } else {
        body.putObject("tool_choice").put("type", "function").putObject("function").put("name", choice.name());
      }
    }
    if (stream) {
      body.put("stream", true);
      if (streamUsage) body.putObject("stream_options").put("include_usage", true);
    }
    return body;
  }

  private HttpRequest post(String path, JsonNode body) {
    return request(path)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json, text/event-stream");
    if (!apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
    return b;
  }

  /**
   * Sends {@code request} until it gets a 2xx response, retrying transient failures by {@link #retry}.
   * Throws LlmException for a final failure and CancelledException when the signal is cancelled.
   */
  private HttpResponse<InputStream> sendChecked(HttpRequest request, Signal signal) {
    for (int attempt = 0; ; attempt++) {
      HttpResponse<InputStream> response;
      try {
        response = send(request, signal);
      } catch (LlmException e) {
        if (attempt >= retry.maxRetries()) throw e;
        pause(retry.delay(attempt), signal);
        continue;
      }
      int status = response.statusCode();
      if (status / 100 == 2) return response;
      String body = readText(response, signal);
      if (!RETRYABLE.contains(status) || attempt >= retry.maxRetries()) throw error(status, body);
      Duration wait = retry.delay(attempt);
      Duration asked = retryAfter(response);
      if (asked != null && asked.compareTo(wait) > 0) wait = retry.cap(asked);
      pause(wait, signal);
    }
  }

  /** Waits {@code delay}, returning early (with CancelledException) when the signal is cancelled. */
  private static void pause(Duration delay, Signal signal) {
    signal.throwIfCancelled();
    if (delay.isZero()) return;
    CountDownLatch woken = new CountDownLatch(1);
    Disposable wake = signal.onCancel(woken::countDown);
    try {
      woken.await(delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw cancelled(e);
    } finally {
      wake.dispose();
    }
    signal.throwIfCancelled();
  }

  /** The {@code Retry-After} header in seconds; null when absent or an HTTP date (not worth parsing). */
  private static Duration retryAfter(HttpResponse<?> response) {
    String value = response.headers().firstValue("Retry-After").orElse(null);
    if (value == null) return null;
    try {
      long seconds = Long.parseLong(value.strip());
      return seconds < 0 ? null : Duration.ofSeconds(seconds);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Sends asynchronously so that cancelling the signal (or interrupting the thread) aborts the exchange. */
  private HttpResponse<InputStream> send(HttpRequest request, Signal signal) {
    signal.throwIfCancelled();
    CompletableFuture<HttpResponse<InputStream>> future =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    Disposable abort = signal.onCancel(() -> future.cancel(true));
    try {
      return future.get();
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw cancelled(e);
    } catch (CancellationException e) {
      throw cancelled(e);
    } catch (ExecutionException e) {
      if (signal.isCancelled()) throw cancelled(e.getCause());
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw new LlmException(providerName + " unreachable: " + cause, cause);
    } finally {
      abort.dispose();
    }
  }

  private JsonNode readJson(HttpResponse<InputStream> response, Signal signal) {
    String text = readText(response, signal);
    JsonNode json = Json.parseOrNull(text);
    if (json == null) throw new LlmException(response.statusCode(), providerName + " answered non-JSON");
    return json;
  }

  private String readText(HttpResponse<InputStream> response, Signal signal) {
    InputStream in = response.body();
    Disposable abort = signal.onCancel(() -> closeQuietly(in));
    try (in) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      if (signal.isCancelled()) throw cancelled(e);
      throw new UncheckedIOException(e);
    } finally {
      abort.dispose();
    }
  }

  private LlmException error(int status, String body) {
    JsonNode json = Json.parseOrNull(body);
    String detail = json == null ? body : json.path("error").path("message").asText(
        json.path("message").asText(json.path("detail").asText(body)));
    if (detail == null || detail.isBlank()) detail = "(no detail)";
    if (detail.length() > 500) detail = detail.substring(0, 500) + "...";
    return new LlmException(status, providerName + " answered " + status + ": " + detail);
  }

  /** Tool arguments are a JSON string by spec, but some providers send the object itself. */
  private static String arguments(JsonNode args) {
    if (args == null || args.isNull() || args.isMissingNode()) return "{}";
    return args.isTextual() ? args.asText() : args.toString();
  }

  /**
   * Rebuilds tool calls from stream deltas. OpenAI sends the id and name once, then the arguments in
   * pieces, keyed by {@code index}; Mistral sends whole calls without an index. A delta without index is
   * matched by id, or continues the last call when it has no id either.
   */
  private static final class ToolCallParts {

    private final List<String[]> parts = new ArrayList<>(); // {id, name, arguments}
    private final Map<Integer, String[]> byIndex = new HashMap<>();

    /** Adds the pieces of one chunk, and reports each one that brings something to {@code out}. */
    void add(JsonNode deltas, Consumer<ToolCallDelta> out) {
      for (JsonNode d : deltas) {
        String id = textOrNull(d.path("id"));
        String[] part = find(d.path("index"), id);
        String newId = null;
        if (id != null && !id.isEmpty() && !id.equals(part[0])) newId = part[0] = id;
        JsonNode fn = d.path("function");
        String name = textOrNull(fn.path("name"));
        String newName = null;
        if (name != null && !name.isEmpty() && !name.equals(part[1])) newName = part[1] = name;
        JsonNode args = fn.path("arguments");
        String piece = args.isMissingNode() || args.isNull() ? "" : args.isTextual() ? args.asText() : args.toString();
        part[2] += piece;
        if (newId != null || newName != null || !piece.isEmpty()) {
          out.accept(new ToolCallDelta(parts.indexOf(part), newId, newName, piece));
        }
      }
    }

    private String[] find(JsonNode index, String id) {
      if (index.canConvertToInt()) {
        return byIndex.computeIfAbsent(index.asInt(), k -> create());
      }
      if (id != null) {
        for (String[] p : parts) {
          if (id.equals(p[0])) return p;
        }
        return create();
      }
      return parts.isEmpty() ? create() : parts.get(parts.size() - 1);
    }

    private String[] create() {
      String[] p = {null, "", ""};
      parts.add(p);
      return p;
    }

    List<ToolCall> build() {
      List<ToolCall> out = new ArrayList<>();
      for (int i = 0; i < parts.size(); i++) {
        String[] p = parts.get(i);
        out.add(new ToolCall(p[0] == null ? "call_" + i : p[0], p[1], p[2]));
      }
      return out;
    }
  }

  private static Usage usage(JsonNode u) {
    if (u == null || !u.isObject()) return Usage.NONE;
    return new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0),
        u.path("completion_tokens_details").path("reasoning_tokens").asInt(0));
  }

  private static String textOrNull(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
  }

  private static CancelledException cancelled(Throwable cause) {
    CancelledException e = new CancelledException("request cancelled");
    if (cause != null) e.initCause(cause);
    return e;
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // closing only to unblock a reader
    }
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
