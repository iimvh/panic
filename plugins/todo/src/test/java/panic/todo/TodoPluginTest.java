package panic.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
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
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.CancelToken;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

class TodoPluginTest {

  @TempDir
  Path dir;

  private final List<String> warnings = new CopyOnWriteArrayList<>();
  private Root root = Context.root("t");
  private ConfigHandle<TodoConfig> todo;

  private static final String TWO = """
      {"items":[{"text":"write tests","status":"done"},{"text":"ship it","status":"in_progress"}]}""";

  @BeforeEach
  void start() {
    todo = root.plugin("todo", new TodoPlugin(warnings::add), new TodoConfig(dir.toString(), 3));
    // todo_write is a write: asked by default, allowed here as panic.example.json does
    root.plugin("permissions", new PermissionsPlugin(),
        new PermissionsConfig(null, Map.of("todo_write", "allow"), 0));
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private ToolResult call(String conversation, String name, String args) {
    return Tools.call(root, new ToolCall("id", name, args), new ToolContext(conversation, new CancelToken()));
  }

  private List<Chat.Section> sections(String conversation) {
    return root.collect(Chat.CONTEXT, new Chat.ContextQuery(conversation));
  }

  @Test
  void writeThenReadPerConversation() {
    assertEquals(ToolResult.ok("no tasks"), call("a", "todo_read", "{}"));
    ToolResult written = call("a", "todo_write", TWO);
    assertEquals(ToolResult.ok("1. [x] write tests\n2. [~] ship it"), written);
    assertEquals(written, call("a", "todo_read", "{}"));
    assertEquals(ToolResult.ok("no tasks"), call("b", "todo_read", "{}"), "lists are per conversation");

    assertEquals(ToolResult.ok("task list cleared"), call("a", "todo_write", "{\"items\":[]}"));
    assertFalse(Files.exists(dir.resolve("a.json")));
  }

  @Test
  void listsSurviveARestart() {
    call("a", "todo_write", TWO);
    root.dispose();
    root = Context.root("t");
    start();
    assertEquals(ToolResult.ok("1. [x] write tests\n2. [~] ship it"), call("a", "todo_read", "{}"));
  }

  @Test
  void badListsAreRefusedAndChangeNothing() {
    call("a", "todo_write", TWO);
    ToolResult status = call("a", "todo_write", "{\"items\":[{\"text\":\"x\",\"status\":\"maybe\"}]}");
    assertTrue(status.isError() && status.content().contains("status"), status.content());
    ToolResult tooMany = call("a", "todo_write",
        "{\"items\":[" + "{\"text\":\"x\",\"status\":\"pending\"},".repeat(3) + "{\"text\":\"y\",\"status\":\"pending\"}]}");
    assertTrue(tooMany.isError() && tooMany.content().contains("at most 3"), tooMany.content());
    ToolResult blank = call("a", "todo_write", "{\"items\":[{\"text\":\"  \",\"status\":\"pending\"}]}");
    assertTrue(blank.isError() && blank.content().contains("items[0].text"), blank.content());
    assertEquals(ToolResult.ok("1. [x] write tests\n2. [~] ship it"), call("a", "todo_read", "{}"));
  }

  @Test
  void writesAreAskedByDefault() {
    root.dispose();
    root = Context.root("t");
    root.plugin("todo", new TodoPlugin(warnings::add), new TodoConfig(dir.toString(), 0));
    ToolResult r = call("a", "todo_write", TWO);
    assertTrue(r.isError() && r.content().contains("denied"), "no front-end answers the question: " + r.content());
  }

  @Test
  void theListIsASectionOfTheSystemPromptWhileThePluginRuns() {
    assertTrue(sections("a").isEmpty(), "an empty list adds nothing");
    call("a", "todo_write", TWO);
    List<Chat.Section> s = sections("a");
    assertEquals(1, s.size());
    assertEquals("Tasks", s.get(0).name());
    assertTrue(s.get(0).text().endsWith("1. [x] write tests\n2. [~] ship it"), s.get(0).text());
    assertTrue(sections("b").isEmpty());

    todo.dispose();
    assertTrue(sections("a").isEmpty());
    assertFalse(Tools.specs(root).stream().map(ToolSpec::name).anyMatch(n -> n.startsWith("todo_")));
  }

  @Test
  void aDamagedFileReadsAsEmptyWithAWarning() throws Exception {
    Files.writeString(dir.resolve("a.json"), "{\"items\":[{\"text\":\"x\",");
    assertEquals(ToolResult.ok("no tasks"), call("a", "todo_read", "{}"));
    assertEquals(1, warnings.size(), warnings.toString());
    assertEquals(ToolResult.ok("1. [x] write tests\n2. [~] ship it"), call("a", "todo_write", TWO), "a write replaces it");
  }

  @Test
  void unsafeIdsGetAnEncodedFileName() {
    call("../x", "todo_write", TWO);
    String name = TodoPlugin.fileName("../x");
    assertTrue(name.startsWith("~") && !name.contains("/"), name);
    assertTrue(Files.exists(dir.resolve(name)));
  }

  @Test
  void commandShowsTheSessionList() {
    call("s1", "todo_write", TWO);
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, "todo", "", new Session("s1", out::append, new CancelToken())));
    assertTrue(out.toString().contains("2. [~] ship it"), out.toString());
    out.setLength(0);
    Commands.run(root, "todo", "", new Session("s2", out::append, new CancelToken()));
    assertTrue(out.toString().contains("no tasks"), out.toString());
  }

  @Test
  void criteriaAreKeptUntilReplacedAndReadableThroughTheHook() {
    call("a", "todo_write", "{\"items\":[{\"text\":\"fix\",\"status\":\"pending\"}],"
        + "\"criteria\":[\"tests pass\",\" \",\"no warnings\"]}");
    assertEquals(ToolResult.ok("1. [~] fix\nAcceptance criteria:\n- tests pass\n- no warnings"),
        call("a", "todo_write", "{\"items\":[{\"text\":\"fix\",\"status\":\"in_progress\"}]}"),
        "updating a status keeps the criteria; blank ones are dropped");

    root.dispose();
    root = Context.root("t");
    start();
    Todo.Plan plan = root.bail(Todo.PLAN, new Todo.Query("a")).orElseThrow();
    assertEquals(List.of("tests pass", "no warnings"), plan.criteria());
    assertTrue(sections("a").get(0).text().contains("- tests pass"));
    assertTrue(root.bail(Todo.PLAN, new Todo.Query("b")).isEmpty(), "no plan, no answer");

    assertEquals(ToolResult.ok("Acceptance criteria:\n- tests pass\n- no warnings"),
        call("a", "todo_write", "{\"items\":[]}"), "criteria alone are a plan");
    assertEquals(ToolResult.ok("task list cleared"), call("a", "todo_write", "{\"items\":[],\"criteria\":[]}"));
    assertTrue(root.bail(Todo.PLAN, new Todo.Query("a")).isEmpty());

    call("a", "todo_write", "{\"items\":[],\"criteria\":[\"x\"]}");
    todo.dispose();
    assertTrue(root.bail(Todo.PLAN, new Todo.Query("a")).isEmpty(), "the hook goes with the plugin");
  }
}
