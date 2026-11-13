package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Plugin;
import context.PluginHandle;
import context.Root;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.CancelToken;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.ToolCall;

/** The conversation store on disk: what survives a restart, and what a damaged file loses. */
class ConversationsPluginTest {

  @TempDir
  Path dir;

  private final List<String> warnings = new CopyOnWriteArrayList<>();
  private Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private ConfigHandle<ConversationsConfig> start() {
    return root.plugin("conversations", new ConversationsPlugin(warnings::add),
        new ConversationsConfig(dir.toString(), true));
  }

  /** Disposes the tree and starts a fresh one on the same directory: what a restart of the app does. */
  private void restart() {
    root.dispose();
    root = Context.root("t");
    start();
  }

  /** The store running right now, seen by a plugin that requires it (as chat and web do). */
  private Conversation get(String id) {
    AtomicReference<Conversations> seen = new AtomicReference<>();
    PluginHandle probe = root.plugin(Plugin.require(Conversations.KEY).then(ctx -> {
      seen.set(ctx.get(Conversations.KEY));
      return null;
    }));
    probe.dispose();
    return seen.get().get(id);
  }

  private static final ToolCall CALL = new ToolCall("c1", "time", "{}");

  private static List<Message> toolTurn() {
    return List.of(Message.user("what time?"), Message.assistant("", List.of(CALL)),
        Message.tool("c1", "12:00"), Message.assistant("noon"));
  }

  private Path file(String id) {
    return dir.resolve(ConversationFile.fileName(id));
  }

  @Test
  void restartKeepsTheHistoryWithToolCalls() {
    start();
    get("a").append(toolTurn().toArray(Message[]::new));
    get("a").append(Message.user("thanks"), Message.assistant("welcome"));
    restart();
    List<Message> expected = new ArrayList<>(toolTurn());
    expected.add(Message.user("thanks"));
    expected.add(Message.assistant("welcome"));
    assertEquals(expected, get("a").recent(0));
    assertEquals(List.of(), get("b").recent(0), "other conversations start empty");
    assertTrue(warnings.isEmpty(), warnings.toString());
  }

  @Test
  void compactionIsKeptAcrossRestarts() {
    start();
    Conversation c = get("a");
    c.append(Message.user("1"), Message.assistant("one"));
    c.append(Message.user("2"), Message.assistant("two"));
    assertTrue(c.compact(c.snapshot().messages().subList(0, 2), "S"));
    c.append(Message.user("3"), Message.assistant("three"));
    restart();
    Conversation.Snapshot s = get("a").snapshot();
    assertEquals("S", s.summary());
    assertEquals(List.of(Message.user("2"), Message.assistant("two"), Message.user("3"), Message.assistant("three")),
        s.messages());
  }

  @Test
  void damagedLinesAreSkippedWithAWarningAndTheFileIsRepaired() throws Exception {
    Files.writeString(file("a"), """
        {"role":"user","content":"1"}
        not json
        {"role":"wizard","content":"?"}
        {"role":"assistant","content":"one"}
        {"role":"user","content":"2"}
        {"role":"assistant","content":"tw""");
    start();
    assertEquals(List.of(Message.user("1"), Message.assistant("one"), Message.user("2")), get("a").recent(0));
    assertEquals(3, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains(":2 skipped"), warnings.get(0));

    get("a").append(Message.assistant("two"));
    warnings.clear();
    restart();
    assertEquals(List.of(Message.user("1"), Message.assistant("one"), Message.user("2"), Message.assistant("two")),
        get("a").recent(0), "the torn line was cut off, so the next append starts a fresh line");
    assertTrue(warnings.isEmpty(), warnings.toString());
  }

  @Test
  void aTurnWithAnUnansweredToolCallIsDropped() throws Exception {
    StringBuilder text = new StringBuilder();
    for (Message m : toolTurn()) {
      if (m.role() != panic.llm.Role.TOOL) text.append(ConversationFile.encode(m)).append('\n');
    }
    text.append(ConversationFile.encode(Message.user("later"))).append('\n');
    text.append(ConversationFile.encode(Message.assistant("ok"))).append('\n');
    Files.writeString(file("a"), text);
    start();
    assertEquals(List.of(Message.user("later"), Message.assistant("ok")), get("a").recent(0),
        "a provider would reject a call without its result");
    assertEquals(1, warnings.size(), warnings.toString());
  }

  @Test
  void concurrentAppendsAreAllKept() throws Exception {
    start();
    int threads = 8;
    int each = 25;
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> running = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      String name = "t" + t;
      running.add(Thread.ofVirtual().start(() -> {
        try {
          go.await();
        } catch (InterruptedException e) {
          return;
        }
        for (int i = 0; i < each; i++) {
          get("a").append(Message.user(name + "-" + i), Message.assistant("ok"));
        }
      }));
    }
    go.countDown();
    for (Thread t : running) t.join();
    List<Message> before = get("a").recent(0);
    restart();
    assertEquals(threads * each * 2, get("a").size());
    assertEquals(before, get("a").recent(0), "same order on disk as in memory");
    assertTrue(warnings.isEmpty(), warnings.toString());
  }

  @Test
  void resetDeletesTheFile() {
    start();
    get("s1").append(Message.user("hi"), Message.assistant("yo"));
    assertTrue(Files.exists(file("s1")));
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, "reset", "", new Session("s1", out::append, new CancelToken())));
    assertTrue(out.toString().contains("conversation cleared"), out.toString());
    assertFalse(Files.exists(file("s1")));
    restart();
    assertEquals(0, get("s1").size());
  }

  @Test
  void unsafeIdsGetAnEncodedFileName() {
    start();
    get("../../etc/passwd").append(Message.user("x"), Message.assistant("y"));
    String name = ConversationFile.fileName("../../etc/passwd");
    assertTrue(name.startsWith("~") && !name.contains("/"), name);
    assertTrue(Files.exists(dir.resolve(name)));
    assertEquals("safe-id_1.jsonl", ConversationFile.fileName("safe-id_1"));
    restart();
    assertEquals(2, get("../../etc/passwd").size());
  }

  @Test
  void memoryOnlyWritesNothing() throws Exception {
    Path sub = dir.resolve("none");
    root.plugin("conversations", new ConversationsPlugin(warnings::add), new ConversationsConfig(sub.toString(), false));
    get("a").append(Message.user("x"), Message.assistant("y"));
    assertEquals(2, get("a").size());
    assertFalse(Files.exists(sub));
  }

  @Test
  void aNewDirectoryIsReadAfterAConfigChange() throws Exception {
    ConfigHandle<ConversationsConfig> h = start();
    get("a").append(Message.user("old"), Message.assistant("dir"));
    Path other = dir.resolve("other");
    h.update(new ConversationsConfig(other.toString(), true));
    assertEquals(0, get("a").size());
    get("a").append(Message.user("new"), Message.assistant("dir"));
    assertTrue(Files.readString(other.resolve("a.jsonl"), StandardCharsets.UTF_8).contains("\"new\""));
    h.update(new ConversationsConfig(dir.toString(), true));
    assertEquals(Message.user("old"), get("a").recent(0).get(0));
  }

  @Test
  void chatAnswersWithTheHistoryOfAPreviousRun() {
    ChatPluginTest.FakeLlm first = new ChatPluginTest.FakeLlm("m");
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, first);
      return null;
    });
    start();
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    Chat.ask(root, new Chat.Ask("c", "hello", new CancelToken(), d -> { })).orElseThrow();

    root.dispose();
    root = Context.root("t");
    ChatPluginTest.FakeLlm second = new ChatPluginTest.FakeLlm("m");
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, second);
      return null;
    });
    start();
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    Chat.ask(root, new Chat.Ask("c", "again", new CancelToken(), d -> { })).orElseThrow();
    assertEquals(List.of(Message.user("hello"), Message.assistant("n=1"), Message.user("again")),
        second.requests.get(0).messages());
    assertNull(get("c").summary());
  }
}
