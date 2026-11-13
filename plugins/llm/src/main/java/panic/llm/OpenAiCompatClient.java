package panic.llm;

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
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * A ChatModel speaking the OpenAI chat-completions protocol ({@code POST {baseUrl}/chat/completions}),
 * which OpenRouter, Mistral and most other providers implement. Blocking, cancellable through the Signal.
 */
public final class OpenAiCompatClient implements ChatModel {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

  private final HttpClient http;
  private final String baseUrl;
  private final String apiKey;
  private final String providerName;

  public OpenAiCompatClient(String providerName, String baseUrl, String apiKey) {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), providerName, baseUrl, apiKey);
  }

  public OpenAiCompatClient(HttpClient http, String providerName, String baseUrl, String apiKey) {
    this.http = Objects.requireNonNull(http, "http");
    this.providerName = Objects.requireNonNull(providerName, "providerName");
    this.baseUrl = stripSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey").strip();
  }

  // ---------------- chat ----------------

  @Override
  public Completion complete(ChatRequest request, Signal signal) {
    HttpResponse<InputStream> response = send(post("/chat/completions", body(request, false)), signal);
    JsonNode json = readJson(response, signal);
    JsonNode choice = json.path("choices").path(0);
    return new Completion(
        Content.text(choice.path("message").path("content")),
        json.path("model").asText(request.model()),
        textOrNull(choice.path("finish_reason")),
        usage(json.path("usage")));
  }

  @Override
  public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta) {
    Objects.requireNonNull(onDelta, "onDelta");
    HttpResponse<InputStream> response = send(post("/chat/completions", body(request, true)), signal);
    if (response.statusCode() / 100 != 2) throw error(response.statusCode(), readText(response, signal));

    StringBuilder text = new StringBuilder();
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
        String piece = Content.text(choice.path("delta").path("content"));
        if (!piece.isEmpty()) {
          text.append(piece);
          onDelta.accept(piece);
        }
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
    return new Completion(text.toString(), model[0], finish[0], usage[0]);
  }

  // ---------------- plain REST ----------------

  /** GET {baseUrl}{path} with the bearer key, as JSON. Throws LlmException on non-2xx. */
  public JsonNode get(String path, Signal signal) {
    HttpRequest request = request(path).GET().build();
    return readJson(send(request, signal), signal);
  }

  public String baseUrl() {
    return baseUrl;
  }

  // ---------------- internals ----------------

  private ObjectNode body(ChatRequest request, boolean stream) {
    if (request.model() == null || request.model().isBlank()) {
      throw new LlmException(0, providerName + ": no model selected");
    }
    ObjectNode body = Json.object();
    body.put("model", request.model());
    ArrayNode messages = body.putArray("messages");
    for (Message m : request.messages()) {
      messages.addObject().put("role", m.role().wire()).put("content", m.content());
    }
    if (request.temperature() != null) body.put("temperature", request.temperature());
    if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());
    if (stream) body.put("stream", true);
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
    if (response.statusCode() / 100 != 2) throw error(response.statusCode(), text);
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

  private static Usage usage(JsonNode u) {
    if (u == null || !u.isObject()) return Usage.NONE;
    return new Usage(u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0));
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
