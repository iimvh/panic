package panic.chat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import panic.llm.ChatRequest;
import panic.llm.Message;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;

/**
 * Token estimates without a tokenizer (no new library, and each provider counts differently): about four
 * characters per token plus a fixed cost per message. The raw estimate is then scaled by a ratio learned from
 * the prompt tokens the provider reports, so the budget follows the real model.
 *
 * One instance per start of the chat plugin: switching the model restarts the plugin and the ratio is learned
 * again. Thread-safe.
 */
final class Tokens {

  /** Role, separators and the like that every message costs on the wire. */
  static final int PER_MESSAGE = 4;
  static final double MIN_RATIO = 0.5;
  static final double MAX_RATIO = 3.0;

  private final AtomicReference<Double> ratio = new AtomicReference<>(1.0);

  /** The raw estimate of one message: characters / 4, rounded up, plus {@link #PER_MESSAGE}. */
  static int raw(Message m) {
    long chars = m.content().length();
    for (ToolCall call : m.toolCalls()) chars += call.name().length() + call.arguments().length();
    return PER_MESSAGE + chars(chars);
  }

  static int raw(ToolSpec spec) {
    long chars = spec.name().length() + (spec.description() == null ? 0 : spec.description().length());
    if (spec.parameters() != null) chars += spec.parameters().toString().length();
    return chars(chars);
  }

  /** What the request should cost as prompt tokens, before correction. */
  static int raw(ChatRequest request) {
    long sum = 0;
    for (Message m : request.messages()) sum += raw(m);
    for (ToolSpec s : request.tools()) sum += raw(s);
    return (int) Math.min(Integer.MAX_VALUE, sum);
  }

  private static int chars(long chars) {
    return (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
  }

  int estimate(Message m) {
    return scale(raw(m));
  }

  int estimate(List<Message> messages) {
    long sum = 0;
    for (Message m : messages) sum += raw(m);
    return scale(sum);
  }

  int estimateTools(List<ToolSpec> specs) {
    long sum = 0;
    for (ToolSpec s : specs) sum += raw(s);
    return scale(sum);
  }

  /**
   * Learns from one answer: {@code reported} prompt tokens for a request whose raw estimate was {@code raw}.
   * Ignored when the provider reported nothing. The ratio moves halfway to what was seen and stays within
   * [{@link #MIN_RATIO}, {@link #MAX_RATIO}], so one odd answer cannot throw the budget off.
   */
  void observe(int raw, int reported) {
    if (raw <= 0 || reported <= 0) return;
    double seen = clamp(reported / (double) raw);
    ratio.updateAndGet(old -> clamp((old + seen) / 2));
  }

  double ratio() {
    return ratio.get();
  }

  private int scale(long raw) {
    return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(raw * ratio.get()));
  }

  private static double clamp(double r) {
    return Math.max(MIN_RATIO, Math.min(MAX_RATIO, r));
  }
}
