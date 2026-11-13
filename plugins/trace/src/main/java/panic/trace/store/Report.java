package panic.trace.store;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import panic.command.Out;
import panic.trace.Span;

/** The text of the {@code traces}, {@code trace} and {@code stats} commands. */
final class Report {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private Report() {
  }

  /** The latest {@code count} traces, newest first, one line each. */
  static void list(Out out, List<Recorded> traces, int count) {
    if (traces.isEmpty()) {
      out.println("no traces in this session yet");
      return;
    }
    for (int i = traces.size() - 1, n = 0; i >= 0 && n < count; i--, n++) {
      Span r = traces.get(i).root();
      StringBuilder line = new StringBuilder()
          .append(shortId(r.traceId())).append("  ").append(time(r.startMicros())).append("  ")
          .append(pad(duration(r.durationMicros()), 7)).append("  ").append(pad(outcome(r), 9));
      long steps = r.longAttr("steps", -1);
      if (steps >= 0) line.append("  ").append(steps).append(steps == 1 ? " step " : " steps");
      long tools = r.longAttr("toolCalls", 0);
      if (tools > 0) line.append("  ").append(tools).append(tools == 1 ? " tool" : " tools");
      long tokens = tokens(r);
      if (tokens > 0) line.append("  ").append(tokens).append(" tok");
      String question = r.stringAttr("question");
      if (question != null) line.append("  \"").append(oneLine(question, 60)).append('"');
      out.println(line.toString());
    }
    if (traces.size() > count) out.println("(" + (traces.size() - count) + " older; traces <count> shows more)");
  }

  /** One trace as an indented tree: offset from the root's start, duration, what matters per span. */
  static void tree(Out out, List<Recorded> matches, String id) {
    if (matches.isEmpty()) {
      out.println("no trace " + id + " in this session");
      return;
    }
    if (matches.size() > 1) {
      out.println(id + " matches " + matches.size() + " traces: "
          + String.join(", ", matches.stream().map(t -> shortId(t.id())).toList()));
      return;
    }
    Recorded t = matches.get(0);
    Span root = t.root();
    out.println("trace " + t.id() + " · " + time(root.startMicros()) + " · " + duration(root.durationMicros())
        + " · " + outcome(root));
    Map<String, List<Span>> children = new HashMap<>();
    for (Span s : t.spans()) {
      if (!s.root()) children.computeIfAbsent(s.parentId(), k -> new ArrayList<>()).add(s);
    }
    print(out, root, root.startMicros(), children, 0);
  }

  private static void print(Out out, Span s, long origin, Map<String, List<Span>> children, int depth) {
    String label = "  ".repeat(depth) + label(s);
    StringBuilder line = new StringBuilder(pad(label, 28)).append(' ')
        .append(pad("+" + duration(s.startMicros() - origin), 8)).append(' ')
        .append(pad(duration(s.durationMicros()), 7));
    String detail = detail(s);
    if (!detail.isEmpty()) line.append("  ").append(detail);
    if (s.status() != Span.Status.OK) {
      line.append("  [").append(s.status().name().toLowerCase(Locale.ROOT));
      if (s.error() != null) line.append(": ").append(oneLine(s.error(), 80));
      line.append(']');
    }
    out.println(line.toString().stripTrailing());
    for (Span c : children.getOrDefault(s.spanId(), List.of())) print(out, c, origin, children, depth + 1);
  }

  /** Latency, tokens and tools over the session's traces. */
  static void stats(Out out, List<Recorded> traces) {
    if (traces.isEmpty()) {
      out.println("no traces in this session yet");
      return;
    }
    List<Long> turns = new ArrayList<>();
    List<Long> llm = new ArrayList<>();
    List<Long> firstToken = new ArrayList<>();
    long prompt = 0;
    long completion = 0;
    int errors = 0;
    int cancelled = 0;
    Map<String, long[]> tools = new TreeMap<>(); // name -> calls, total micros, errors
    for (Recorded t : traces) {
      Span r = t.root();
      turns.add(r.durationMicros());
      if (r.status() == Span.Status.ERROR) errors++;
      if (r.status() == Span.Status.CANCELLED) cancelled++;
      prompt += r.longAttr("promptTokens", 0);
      completion += r.longAttr("completionTokens", 0);
      for (Span s : t.spans()) {
        switch (s.name()) {
          case "llm" -> {
            llm.add(s.durationMicros());
            long first = s.longAttr("firstTokenMicros", -1);
            if (first >= 0) firstToken.add(first);
          }
          case "tool" -> {
            long[] agg = tools.computeIfAbsent(String.valueOf(s.stringAttr("tool")), k -> new long[3]);
            agg[0]++;
            agg[1] += s.durationMicros();
            if (s.status() != Span.Status.OK) agg[2]++;
          }
          default -> { }
        }
      }
    }
    StringBuilder head = new StringBuilder().append(traces.size()).append(traces.size() == 1 ? " turn" : " turns");
    if (errors + cancelled > 0) head.append(" (").append(errors).append(" failed, ").append(cancelled)
        .append(" cancelled)");
    head.append(" · tokens ").append(prompt).append(" in / ").append(completion).append(" out");
    out.println(head.toString());
    out.println("turn  " + percentiles(turns));
    if (!llm.isEmpty()) {
      String line = "llm   " + llm.size() + (llm.size() == 1 ? " call" : " calls") + " · " + percentiles(llm);
      if (!firstToken.isEmpty()) line += " · first token p50 " + duration(percentile(firstToken, 50));
      out.println(line);
    }
    if (!tools.isEmpty()) {
      out.println("tools");
      tools.forEach((name, agg) -> out.println("  " + pad(name, 18) + " " + agg[0] + (agg[0] == 1 ? " call " : " calls")
          + " · avg " + duration(agg[1] / agg[0]) + (agg[2] > 0 ? " · " + agg[2] + " failed" : "")));
    }
  }

  private static String label(Span s) {
    return switch (s.name()) {
      case "llm" -> "llm · step " + s.longAttr("step", 0);
      case "tool" -> "tool · " + s.stringAttr("tool");
      case "review" -> "review · " + s.longAttr("attempt", 0);
      default -> s.name();
    };
  }

  private static String detail(Span s) {
    List<String> parts = new ArrayList<>();
    switch (s.name()) {
      case "turn" -> {
        add(parts, s.stringAttr("model"));
        add(parts, s.stringAttr("stop"));
        long steps = s.longAttr("steps", -1);
        if (steps >= 0) parts.add(steps + (steps == 1 ? " step" : " steps"));
        tokens(parts, s);
      }
      case "llm" -> {
        tokens(parts, s);
        long first = s.longAttr("firstTokenMicros", -1);
        if (first >= 0) parts.add("first token " + duration(first));
        long calls = s.longAttr("toolCalls", 0);
        if (calls > 0) parts.add(calls + (calls == 1 ? " tool call" : " tool calls"));
      }
      case "tool" -> {
        long approval = s.longAttr("approvalMicros", -1);
        if (approval >= 1_000) parts.add("approval " + duration(approval));
        long chars = s.longAttr("outputChars", -1);
        if (chars >= 0) parts.add(chars + " chars" + (Boolean.TRUE.equals(s.attrs().get("truncated")) ? " (cut)" : ""));
        if (Boolean.TRUE.equals(s.attrs().get("refused"))) parts.add("refused: repeated");
      }
      case "review" -> {
        add(parts, s.stringAttr("verdict"));
        if ("fail".equals(s.stringAttr("verdict"))) add(parts, oneLine(String.valueOf(s.stringAttr("reason")), 60));
        tokens(parts, s);
      }
      case "compaction" -> {
        parts.add(s.longAttr("messages", 0) + " messages");
        tokens(parts, s);
      }
      default -> { }
    }
    return String.join(" · ", parts);
  }

  private static void add(List<String> parts, String value) {
    if (value != null && !value.isBlank()) parts.add(value);
  }

  private static void tokens(List<String> parts, Span s) {
    long in = s.longAttr("promptTokens", 0);
    long out = s.longAttr("completionTokens", 0);
    if (in + out > 0) parts.add(in + "+" + out + " tok");
  }

  private static long tokens(Span s) {
    return s.longAttr("promptTokens", 0) + s.longAttr("completionTokens", 0);
  }

  private static String outcome(Span root) {
    if (root.status() != Span.Status.OK) return root.status().name().toLowerCase(Locale.ROOT);
    String stop = root.stringAttr("stop");
    return stop == null ? "ok" : stop;
  }

  private static String percentiles(List<Long> values) {
    return "p50 " + duration(percentile(values, 50)) + " · p95 " + duration(percentile(values, 95))
        + " · max " + duration(Collections.max(values));
  }

  /** Nearest-rank percentile. */
  static long percentile(List<Long> values, int p) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
  }

  /** {@code 850µs}, {@code 12ms}, {@code 1.25s}, {@code 2m05s}. */
  static String duration(long micros) {
    if (micros < 1_000) return micros + "µs";
    if (micros < 1_000_000) return micros / 1_000 + "ms";
    if (micros < 60_000_000) return String.format(Locale.ROOT, "%.2fs", micros / 1_000_000.0);
    long s = micros / 1_000_000;
    return String.format(Locale.ROOT, "%dm%02ds", s / 60, s % 60);
  }

  static String shortId(String id) {
    return id.length() <= 8 ? id : id.substring(0, 8);
  }

  private static String time(long epochMicros) {
    return TIME.format(Instant.ofEpochSecond(epochMicros / 1_000_000, (epochMicros % 1_000_000) * 1_000)
        .atZone(ZoneId.systemDefault()));
  }

  private static String oneLine(String text, int max) {
    String s = text.replaceAll("\\s+", " ").strip();
    return s.length() <= max ? s : s.substring(0, max) + "…";
  }

  private static String pad(String s, int width) {
    return s.length() >= width ? s : s + " ".repeat(width - s.length());
  }
}
