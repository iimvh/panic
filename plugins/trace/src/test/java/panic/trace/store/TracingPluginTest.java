package panic.trace.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import core.Disposable;
import core.Signal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.command.Commands;
import panic.command.Session;
import panic.trace.Span;
import panic.trace.Trace;

class TracingPluginTest {

  private static final long DAY = 86_400L * 1_000_000L;

  @TempDir
  Path dir;

  private final Root root = Context.root("t");
  private final List<String> warnings = new CopyOnWriteArrayList<>();

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private ConfigHandle<TracingConfig> tracing(TracingConfig cfg) {
    return root.plugin("tracing", new TracingPlugin(warnings::add), cfg);
  }

  private ConfigHandle<TracingConfig> tracing() {
    return tracing(new TracingConfig(dir.toString(), 0, null));
  }

  /** A finished span, emitted the way chat does: children first, root last. */
  private static Span span(String trace, String id, String parent, String name, long start, long micros,
      Map<String, Object> attrs) {
    return new Span(trace, id, parent, name, start, micros, Span.Status.OK, null, attrs);
  }

  /** Emits a two-span turn (one llm call) of conversation {@code c}, started at {@code start}. */
  private void turn(String c, String trace, long start, String question) {
    root.emit(Trace.SPAN, span(trace, trace + "-llm", trace + "-root", "llm", start + 10, 2_000,
        Map.of("step", 1, "promptTokens", 10, "completionTokens", 5, "firstTokenMicros", 400)));
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put(Trace.CONVERSATION, c);
    attrs.put("question", question);
    attrs.put("stop", "done");
    attrs.put("steps", 1);
    attrs.put("promptTokens", 10);
    attrs.put("completionTokens", 5);
    root.emit(Trace.SPAN, span(trace, trace + "-root", null, "turn", start, 5_000, attrs));
  }

  private String run(String command, String args, String session) {
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, command, args, new Session(session, out::append, never())));
    return out.toString();
  }

  private static Signal never() {
    return new Signal() {
      @Override
      public boolean isCancelled() {
        return false;
      }

      @Override
      public Disposable onCancel(Runnable listener) {
        return () -> { };
      }

      @Override
      public void throwIfCancelled() {
      }
    };
  }

  /** Waits until the writer's cleaning at start has made {@code file} hold {@code lines} lines (or removed it). */
  private static void awaitLines(Path file, int lines) throws Exception {
    long until = System.nanoTime() + 5_000_000_000L;
    while (count(file) != lines) {
      if (System.nanoTime() > until) assertEquals(lines, count(file), file + " was not cleaned");
      Thread.sleep(10);
    }
  }

  private static int count(Path file) throws Exception {
    return Files.exists(file) ? Files.readAllLines(file).size() : 0;
  }

  private Path file(String conversation) {
    return dir.resolve(conversation + ".jsonl");
  }

  @Test
  void aTraceIsKeptOnceItsRootEndsAndServedByTheHooks() {
    tracing();
    long now = TraceStore.nowMicros();
    root.emit(Trace.SPAN, span("t1", "t1-llm", "t1-root", "llm", now, 10, Map.of()));
    assertTrue(root.bail(Trace.GET, new Trace.Query("c", "t1")).isEmpty(), "not a trace before its root ends");

    turn("c", "t1", now, "first");
    turn("c", "t2", now + 100, "second");
    turn("other", "t3", now + 200, "elsewhere");

    List<Span> spans = root.bail(Trace.GET, new Trace.Query("c", "t1")).orElseThrow().spans();
    assertEquals(List.of("turn", "llm", "llm"), spans.stream().map(Span::name).toList(),
        "the span emitted before the root belongs to the trace; in the order they started");
    assertEquals(1L, spans.get(2).attrs().get("step"));

    List<Span> roots = root.bail(Trace.LIST, new Trace.ListQuery("c", 0)).orElseThrow().roots();
    assertEquals(List.of("t2", "t1"), roots.stream().map(Span::traceId).toList(), "newest first, per conversation");
    assertEquals(List.of("t2"),
        root.bail(Trace.LIST, new Trace.ListQuery("c", 1)).orElseThrow().roots().stream().map(Span::traceId).toList());
    assertTrue(root.bail(Trace.GET, new Trace.Query("other", "t1")).isEmpty(), "traces stay in their conversation");
  }

  @Test
  void aUniquePrefixFindsATrace() {
    tracing();
    long now = TraceStore.nowMicros();
    turn("c", "abc123", now, "q");
    turn("c", "abd456", now + 1, "q");
    assertEquals("abc123", root.bail(Trace.GET, new Trace.Query("c", "abc")).orElseThrow().spans().get(0).traceId());
    assertTrue(root.bail(Trace.GET, new Trace.Query("c", "ab")).isEmpty(), "ambiguous");
    assertTrue(run("trace", "ab", "c").contains("matches 2 traces"));
  }

  @Test
  void tracesSurviveARestart() throws Exception {
    ConfigHandle<TracingConfig> h = tracing();
    long now = TraceStore.nowMicros();
    turn("c", "t1", now, "persisted");
    h.dispose(); // drains the writer
    assertEquals(2, Files.readAllLines(file("c")).size(), "one line per span");

    tracing();
    List<Span> spans = root.bail(Trace.GET, new Trace.Query("c", "t1")).orElseThrow().spans();
    assertEquals(2, spans.size());
    assertEquals("persisted", spans.get(0).stringAttr("question"));
    assertEquals(5L, spans.get(1).attrs().get("completionTokens"), "numbers come back as numbers");
    assertTrue(warnings.isEmpty(), warnings.toString());
  }

  @Test
  void tracesRecordedBeforeTheFileIsReadAreMergedWithIt() throws Exception {
    ConfigHandle<TracingConfig> h = tracing();
    long now = TraceStore.nowMicros();
    turn("c", "old", now, "before");
    h.dispose();

    tracing();
    turn("c", "new", now + 10, "after"); // recorded before anyone read the conversation
    List<Span> roots = root.bail(Trace.LIST, new Trace.ListQuery("c", 0)).orElseThrow().roots();
    assertEquals(List.of("new", "old"), roots.stream().map(Span::traceId).toList());
  }

  @Test
  void damagedLinesAreSkippedAndRepaired() throws Exception {
    ConfigHandle<TracingConfig> h = tracing();
    turn("c", "t1", TraceStore.nowMicros(), "q");
    h.dispose();
    Files.writeString(file("c"), "not json\n{\"traceId\":\"x\"", StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    tracing();
    assertEquals(1, root.bail(Trace.LIST, new Trace.ListQuery("c", 0)).orElseThrow().roots().size());
    awaitLines(file("c"), 2); // rewritten clean at start
    assertFalse(warnings.isEmpty(), "the damage is reported");
  }

  @Test
  void oldTracesAreDroppedAtStart() throws Exception {
    ConfigHandle<TracingConfig> h = tracing();
    long now = TraceStore.nowMicros();
    turn("c", "ancient", now - 10 * DAY, "old");
    turn("c", "recent", now, "new");
    turn("gone", "g", now - 10 * DAY, "old");
    h.dispose();

    tracing(new TracingConfig(dir.toString(), 7, null));
    List<Span> roots = root.bail(Trace.LIST, new Trace.ListQuery("c", 0)).orElseThrow().roots();
    assertEquals(List.of("recent"), roots.stream().map(Span::traceId).toList());
    awaitLines(file("c"), 2);
    awaitLines(file("gone"), 0);
    assertFalse(Files.exists(file("gone")), "a file left empty is deleted");
  }

  @Test
  void eachConversationKeepsItsNewestTraces() {
    tracing(new TracingConfig(dir.toString(), 0, 2));
    long now = TraceStore.nowMicros();
    for (int i = 1; i <= 3; i++) turn("c", "t" + i, now + i, "q" + i);
    List<Span> roots = root.bail(Trace.LIST, new Trace.ListQuery("c", 0)).orElseThrow().roots();
    assertEquals(List.of("t3", "t2"), roots.stream().map(Span::traceId).toList());
  }

  @Test
  void commandsReportOnTheSessionsTraces() {
    tracing();
    long now = TraceStore.nowMicros();
    turn("s", "aaaa1111bbbb", now, "what time is it?");
    root.emit(Trace.SPAN, new Span("cccc2222", "tool", "r", "tool", now + 5, 1_200, Span.Status.ERROR, "denied",
        Map.of("tool", "write_file")));
    root.emit(Trace.SPAN, new Span("cccc2222", "r", null, "turn", now + 1, 3_000_000, Span.Status.OK, null,
        Map.of(Trace.CONVERSATION, "s", "stop", "max_steps", "toolCalls", 1)));

    String list = run("traces", "", "s");
    assertTrue(list.indexOf("cccc2222") < list.indexOf("aaaa1111"), "newest first:\n" + list);
    assertTrue(list.contains("\"what time is it?\"") && list.contains("15 tok") && list.contains("max_steps"), list);
    assertTrue(run("traces", "1", "s").contains("1 older"));
    assertTrue(run("traces", "x", "s").contains("positive number"));

    String tree = run("trace", "aaaa", "s");
    assertTrue(tree.startsWith("trace aaaa1111bbbb"), tree);
    assertTrue(tree.contains("\n  llm · step 1") && tree.contains("10+5 tok") && tree.contains("first token 400µs"),
        tree);
    assertTrue(run("trace", "cccc", "s").contains("tool · write_file") && run("trace", "cccc", "s")
        .contains("[error: denied]"));
    assertTrue(run("trace", "zzzz", "s").contains("no trace zzzz"));

    String stats = run("stats", "", "s");
    assertTrue(stats.startsWith("2 turns · tokens 10 in / 5 out"), stats);
    assertTrue(stats.contains("llm   1 call") && stats.contains("write_file") && stats.contains("1 failed"), stats);
    assertTrue(run("stats", "", "empty").contains("no traces"));
  }

  @Test
  void aConfigChangeMovesToTheNewDirectory() throws Exception {
    ConfigHandle<TracingConfig> h = tracing();
    Path other = dir.resolve("elsewhere");
    h.update(new TracingConfig(other.toString(), 0, null));
    turn("c", "t1", TraceStore.nowMicros(), "q");
    h.dispose();
    assertTrue(Files.exists(other.resolve("c.jsonl")));
    assertFalse(Files.exists(file("c")));
  }

  @Test
  void whatIsQueuedIsWrittenEvenWhenDisposedAtOnce() throws Exception {
    for (int i = 0; i < 20; i++) {
      ConfigHandle<TracingConfig> h = tracing(new TracingConfig(dir.resolve("r" + i).toString(), 0, null));
      turn("c", "t" + i, TraceStore.nowMicros(), "q");
      h.dispose();
      assertEquals(2, count(dir.resolve("r" + i).resolve("c.jsonl")), "round " + i);
    }
  }

  @Test
  void nothingIsServedOnceDisposed() {
    tracing().dispose();
    turn("c", "t1", TraceStore.nowMicros(), "q");
    assertTrue(root.bail(Trace.GET, new Trace.Query("c", "t1")).isEmpty());
    assertFalse(Commands.run(root, "traces", "", new Session("c", s -> { }, never())));
  }

  @Test
  void unsafeConversationIdsGetEncodedFileNames() {
    assertEquals("ok-id_1.jsonl", TraceFile.fileName("ok-id_1"));
    assertTrue(TraceFile.fileName("../escape").startsWith("~"));
  }

  @Test
  void percentilesAndDurations() {
    List<Long> v = new ArrayList<>(List.of(5L, 1L, 4L, 2L, 3L));
    assertEquals(3, Report.percentile(v, 50));
    assertEquals(5, Report.percentile(v, 95));
    assertEquals("850µs", Report.duration(850));
    assertEquals("12ms", Report.duration(12_400));
    assertEquals("1.25s", Report.duration(1_250_000));
    assertEquals("2m05s", Report.duration(125_000_000));
  }
}
