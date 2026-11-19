package panic.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.Session;
import panic.command.UsageException;
import panic.tools.Workspace;
import panic.util.CancelToken;

class FolderPickerPluginTest {

  @TempDir
  Path dir;

  private final Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private Optional<Workspace.Picked> pick() {
    return Hooks.bail(root, Workspace.PICK, new Workspace.PickQuery(new CancelToken()));
  }

  private String pickCommand() {
    StringBuilder text = new StringBuilder();
    Commands.run(root, "workspace", "pick", new Session("c", text::append, new CancelToken()));
    return text.toString().strip();
  }

  @Test
  void answersWithTheChosenFolder() {
    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> dir.toString()), FolderPickerConfig.DEFAULT);
    assertEquals(dir.toAbsolutePath().normalize(), pick().orElseThrow().path());
  }

  @Test
  void cancelledOrMissingDialogAnswersNothing() {
    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> null), FolderPickerConfig.DEFAULT);
    assertTrue(pick().isEmpty());
    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> {
      throw new IOException("no zenity");
    }), FolderPickerConfig.DEFAULT);
    assertTrue(pick().isEmpty());
  }

  @Test
  void disabledAnswersNothing() {
    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> dir.toString()), new FolderPickerConfig(false, 0));
    assertTrue(pick().isEmpty());
  }

  @Test
  void workspacePickAddsTheFolder() throws IOException {
    Path chosen = Files.createDirectories(dir.resolve("chosen")).toRealPath();
    root.plugin("workspace", new WorkspacePlugin(s -> { }), new WorkspaceConfig(dir.resolve("data").toString()));
    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> chosen.toString()), FolderPickerConfig.DEFAULT);
    assertEquals("added " + chosen, pickCommand());
    assertEquals(1, Hooks.bail(root, Workspace.LIST, Workspace.Listing.ALL).orElseThrow().projects().size());
  }

  @Test
  void workspacePickWithoutPickerTellsToTypeAPath() {
    root.plugin("workspace", new WorkspacePlugin(s -> { }), new WorkspaceConfig(dir.resolve("data").toString()));
    assertTrue(pickCommand().contains("workspace add <path>"));
  }

  private String command(String session, String line) {
    StringBuilder text = new StringBuilder();
    Commands.run(root, "workspace", line, new Session(session, text::append, new CancelToken()));
    return text.toString().strip();
  }

  @Test
  void workspaceOpenShowsAProjectInTheFileManager() throws IOException {
    Path project = Files.createDirectories(dir.resolve("proj")).toRealPath();
    Path other = Files.createDirectories(dir.resolve("other")).toRealPath();
    List<Path> opened = new ArrayList<>();
    root.plugin("workspace", new WorkspacePlugin(s -> { }), new WorkspaceConfig(dir.resolve("data").toString()));
    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> null, opened::add), FolderPickerConfig.DEFAULT);
    command("c", "use " + project);

    assertEquals("opened " + project, command("c", "open"), "no path: this chat's project");
    assertEquals("opened " + project, command("x", "open " + project));
    assertEquals(other + " is not a project", command("x", "open " + other), "only folders the user added");
    assertThrows(UsageException.class, () -> command("x", "open"), "no path and no project");
    assertEquals(List.of(project, project), opened);
  }

  @Test
  void workspaceOpenWithoutFileManagerGivesThePath() throws IOException {
    Path project = Files.createDirectories(dir.resolve("proj")).toRealPath();
    root.plugin("workspace", new WorkspacePlugin(s -> { }), new WorkspaceConfig(dir.resolve("data").toString()));
    command("c", "use " + project);
    assertTrue(command("c", "open").endsWith("the folder is " + project), "no folder-picker");

    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> null, f -> {
      throw new IOException("no xdg-open");
    }), FolderPickerConfig.DEFAULT);
    assertTrue(command("c", "open").endsWith("the folder is " + project), "no file manager");

    root.plugin("folder-picker", new FolderPickerPlugin((s, t) -> null, f -> { }), new FolderPickerConfig(false, 0));
    assertTrue(Hooks.bail(root, Workspace.REVEAL, new Workspace.RevealQuery(project)).isEmpty(), "disabled");
  }
}
