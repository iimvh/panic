package panic.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversations;
import panic.llm.Llm;
import panic.llm.ToolCall;
import panic.todo.TodoConfig;
import panic.todo.TodoPlugin;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.trace.Span;
import panic.trace.Trace;
import panic.util.CancelToken;

/** Stop hooks in the whole loop: plan with criteria, answer, hook, then the reviewer. */
@DisabledOnOs(OS.WINDOWS)
class StopHooksPluginTest {

  @TempDir
  Path dir;

  private final Root root = Context.root("t");
  private final ReviewPluginTest.TwoModels llm = new ReviewPluginTest.TwoModels();
  private final List<Chat.Reviewed> heard = new CopyOnWriteArrayList<>();
  private final List<Span> spans = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("spans", ctx -> {
      ctx.on(Trace.SPAN, spans::add);
      return null;
    });
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("permissions", new PermissionsPlugin(), new PermissionsConfig(null, Map.of("todo_write", "allow"), 0));
    root.plugin("todo", new TodoPlugin(w -> { }), new TodoConfig(dir.resolve("todo").toString(), 0));
    root.plugin("review", new ReviewPlugin(), ReviewConfig.DEFAULT);
    root.plugin("chat", new ChatPlugin(), ChatConfig.DEFAULT);
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private void hooks(String... commands) {
    root.plugin("stop-hooks", new StopHooksPlugin(), new StopHooksConfig(
        List.of(commands).stream().map(c -> new StopHooksConfig.Hook(c, 10)).toList(), dir.toString(), null, 0));
  }

  private Chat.Reply ask(String text) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), d -> { }, null, heard::add)).orElseThrow();
  }

  private static ToolCall plan() {
    return new ToolCall("p", "todo_write",
        "{\"items\":[{\"text\":\"serve it\",\"status\":\"in_progress\"}],\"criteria\":[\"the server answers\"]}");
  }

  @Test
  void aBlockingHookSendsTheAgentBackWithItsReason() throws Exception {
    // blocks until the agent has made the file, like `curl -fsS localhost:5174` would until the server runs
    hooks("test -f up || { echo 'nothing answers on port 5174' >&2; exit 2; }");
    llm.agent(r -> ReviewPluginTest.calls(plan()))
        .agent(r -> ReviewPluginTest.answer("The dev server is running."))
        .agent(r -> {
          try {
            Files.writeString(dir.resolve("up"), "");
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          return ReviewPluginTest.answer("Started it; it answers now.");
        });
    llm.verdict("{\"pass\": true, \"reason\": \"the hook passed\"}");

    Chat.Reply reply = ask("start the dev server");
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("Started it; it answers now.", reply.text());
    assertFalse(heard.get(0).verdict().pass());
    assertTrue(heard.get(0).verdict().reason().contains("nothing answers on port 5174"), heard.get(0).verdict().reason());
    String feedback = llm.agent.get(2).messages().get(llm.agent.get(2).messages().size() - 1).content();
    assertTrue(feedback.startsWith(Chat.REVIEW_FEEDBACK_PREFIX + "stop hook test -f up"), feedback);
    assertEquals(1, llm.reviewer.size(), "a blocked answer is not reviewed; the next one is");
    String brief = llm.reviewer.get(0).messages().get(1).content();
    assertTrue(brief.contains("Checks run by the harness") && brief.contains("passed (exit 0"), brief);
    assertTrue(spans.stream().anyMatch(s -> s.name().equals("stop") && s.longAttr("blocked", -1) == 1));
  }

  @Test
  void aBlockDecisionOnStdoutBlocksAndTheInputIsLikeClaudeCodes() throws Exception {
    hooks("cat > input.json; grep -q '\"stop_hook_active\":false' input.json "
        + "&& echo '{\"decision\":\"block\",\"reason\":\"run the tests first\"}' || true");
    llm.agent(r -> ReviewPluginTest.calls(plan())).agent(r -> ReviewPluginTest.answer("done"));
    llm.verdict("{\"pass\": true}");
    ask("do it");
    assertEquals("stop hook " + "cat > input.json; grep -q '\"stop_hook_active\":false' input.json "
        + "&& echo '{\"decision\":\"block\",\"reason\":\"run the tests first\"}' || true: run the tests first",
        heard.get(0).verdict().reason());
    String input = Files.readString(dir.resolve("input.json"));
    assertTrue(input.contains("\"hook_event_name\":\"Stop\"") && input.contains("\"session_id\":\"c\"")
        && input.contains("\"stop_hook_active\":true"), "the second run knows it already blocked: " + input);
  }

  @Test
  void aBrokenHookIsReportedAndBlocksNothing() {
    hooks("exit 1", "sleep 0");
    llm.agent(r -> ReviewPluginTest.calls(plan())).agent(r -> ReviewPluginTest.answer("done"));
    llm.verdict("{\"pass\": true}");
    Chat.Reply reply = ask("do it");
    assertEquals(Chat.DONE, reply.stop());
    assertTrue(heard.get(0).failed() && heard.get(0).error().contains("exit 1"), heard.toString());
    assertTrue(heard.get(1).verdict().pass(), "the reviewer still judged");
    Span stop = spans.stream().filter(s -> s.name().equals("stop")).findFirst().orElseThrow();
    assertEquals(Span.Status.ERROR, stop.status());
  }

  @Test
  void withoutCriteriaHooksDoNotRun() {
    hooks("touch ran");
    llm.agent(r -> ReviewPluginTest.answer("hello"));
    assertEquals("hello", ask("hi").text());
    assertFalse(Files.exists(dir.resolve("ran")));
    assertTrue(spans.stream().noneMatch(s -> s.name().equals("stop")));
  }

  @Test
  void hooksRunInTheConversationsProject() throws Exception {
    Path project = Files.createDirectories(dir.resolve("project"));
    root.plugin("workspace", ctx -> {
      ctx.hook(panic.tools.Workspace.ROOT, q -> project);
      return null;
    });
    root.plugin("stop-hooks", new StopHooksPlugin(),
        new StopHooksConfig(List.of(new StopHooksConfig.Hook("pwd > where", 10)), dir.toString(), false, 0));
    llm.agent(r -> ReviewPluginTest.answer("hello"));
    ask("hi");
    assertEquals(project.toRealPath().toString(), Files.readString(project.resolve("where")).strip());
    assertTrue(heard.isEmpty(), "a hook that passes says nothing: " + heard);
  }
}
