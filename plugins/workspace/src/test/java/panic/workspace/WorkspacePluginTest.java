package panic.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.chat.Conversations;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.Session;
import panic.llm.ToolCall;
import panic.tools.FilesConfig;
import panic.tools.FilesPlugin;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.tools.Workspace;
import panic.util.CancelToken;

class WorkspacePluginTest {

  @TempDir
  Path dir;

  private final Root root = Context.root("t");
  private final List<String> warnings = new ArrayList<>();
  private Path data;
  private Path project;
  private Path other;

  @BeforeEach
  void setUp() throws IOException {
    data = dir.resolve("data");
    project = Files.createDirectories(dir.resolve("meeting-room")).toRealPath();
    other = Files.createDirectories(dir.resolve("other")).toRealPath();
    Files.writeString(project.resolve("a.txt"), "in project\n");
    Files.writeString(other.resolve("a.txt"), "in other\n");
    install();
  }

  private void install() {
    root.plugin("workspace", new WorkspacePlugin(warnings::add), new WorkspaceConfig(data.toString()));
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private String run(String session, String action, String target) {
    StringBuilder text = new StringBuilder();
    String line = target == null ? action : action + " \"" + target + "\"";
    Commands.run(root, "workspace", line, new Session(session, text::append, new CancelToken()));
    return text.toString().strip();
  }

  private Path rootOf(String id) {
    return Hooks.bail(root, Workspace.ROOT, new Workspace.Query(id)).orElse(null);
  }

  private Workspace.Projects projects() {
    return Hooks.bail(root, Workspace.LIST, Workspace.Listing.ALL).orElseThrow();
  }

  @Test
  void aChatWithoutProjectHasNoRoot() {
    assertNull(rootOf("c1"));
    assertTrue(projects().projects().isEmpty());
  }

  @Test
  void useAddsTheProjectAndBindsTheChat() {
    assertEquals("this chat works in " + project, run("c1", "use", project.toString()));
    assertEquals(project, rootOf("c1"));
    assertNull(rootOf("c2"), "another chat is not affected");
    Workspace.Project p = projects().projects().get(0);
    assertEquals(project, p.path());
    assertEquals("meeting-room", p.name());
    assertEquals(List.of("c1"), p.conversations());
  }

  @Test
  void addRegistersWithoutBinding() {
    run("c1", "add", project.toString());
    assertNull(rootOf("c1"));
    assertEquals(1, projects().projects().size());
  }

  @Test
  void aBadPathIsReportedAndChangesNothing() {
    assertTrue(run("c1", "add", dir.resolve("nope").toString()).startsWith("folder not found"));
    assertTrue(run("c1", "add", project.resolve("a.txt").toString()).startsWith("not a folder"));
    assertTrue(projects().projects().isEmpty());
  }

  @Test
  void removingAProjectHidesItsChatsUntilItIsAddedAgain() {
    run("c1", "use", project.toString());
    assertTrue(run("c1", "remove", null).startsWith("removed " + project));
    assertNull(rootOf("c1"), "no project any more: default workspace");
    assertTrue(projects().projects().isEmpty());
    assertEquals(List.of("c1"), projects().hidden());
    run("c9", "add", project.toString());
    assertEquals(project, rootOf("c1"), "the chat is back");
    assertTrue(projects().hidden().isEmpty());
  }

  @Test
  void resetReturnsToTheDefault() {
    run("c1", "use", project.toString());
    run("c1", "reset", null);
    assertNull(rootOf("c1"));
    assertEquals(1, projects().projects().size(), "the project stays");
  }

  @Test
  void deletingAConversationDropsItsEntry() {
    run("c1", "use", project.toString());
    root.emit(Conversations.DELETED, new Conversations.Deleted("c1"));
    assertNull(rootOf("c1"));
    assertTrue(projects().projects().get(0).conversations().isEmpty());
  }

  @Test
  void theStateSurvivesARestart() {
    run("c1", "use", project.toString());
    root.plugin("workspace", new WorkspacePlugin(warnings::add), new WorkspaceConfig(data.toString()));
    assertEquals(project, rootOf("c1"));
  }

  @Test
  void aDamagedFileIsIgnoredWithAWarning() throws IOException {
    Files.createDirectories(data);
    Files.writeString(data.resolve("projects.json"), "{not json");
    root.plugin("workspace", new WorkspacePlugin(warnings::add), new WorkspaceConfig(data.toString()));
    assertTrue(projects().projects().isEmpty());
    assertFalse(warnings.isEmpty());
  }

  @Test
  void thePromptTellsTheModelWhereItWorks() {
    String none = section("c1");
    assertTrue(none.contains("No project is selected"), none);
    run("c1", "use", project.toString());
    String text = section("c1");
    assertTrue(text.contains("\"meeting-room\"") && text.contains(project.toString()), text);
  }

  @Test
  void aSubAgentIsToldAboutTheProjectOfItsOrigin() {
    run("c1", "use", project.toString());
    String text = root.collect(Chat.CONTEXT, new Chat.ContextQuery("c1~explore-1", "c1", "explore")).stream()
        .map(Chat.Section::text).findFirst().orElse("");
    assertTrue(text.contains("\"meeting-room\""), text);
  }

  private String section(String id) {
    return root.collect(Chat.CONTEXT, new Chat.ContextQuery(id)).stream().map(Chat.Section::text).findFirst()
        .orElse("");
  }

  @Test
  void fileToolsFollowTheChatsProject() {
    root.plugin("files", new FilesPlugin(), new FilesConfig(other.toString(), 0, 0, 0));
    root.plugin("permissions", new PermissionsPlugin(), new PermissionsConfig(Map.of("write", "allow"), null, 0));
    run("c1", "use", project.toString());
    assertEquals("     1\tin project\n", read("c1", "c1", "a.txt").content());
    assertEquals("     1\tin other\n", read("c2", "c2", "a.txt").content(), "no project: the configured root");
    assertEquals("     1\tin project\n", read("sub-agent", "c1", "a.txt").content(),
        "a sub-agent works where its origin does");
    ToolResult outside = read("c1", "c1", "../other/a.txt");
    assertTrue(outside.isError() && outside.content().contains("outside the workspace"), outside.content());
  }

  private ToolResult read(String conversation, String origin, String path) {
    return Tools.call(root, new ToolCall("id", "read_file", "{\"path\":\"" + path + "\"}"),
        new ToolContext(conversation, new CancelToken(), null, origin, null, null, null, null));
  }
}
