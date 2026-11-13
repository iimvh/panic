package panic.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.Session;
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
}
