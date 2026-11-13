package panic.workspace;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: projects (workspaces) for conversations, and the native folder dialog. */
public final class WorkspacePlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of("workspace", WorkspaceConfig.class, WorkspaceConfig.DEFAULT,
            () -> new WorkspacePlugin(System.err::println)),
        PluginSpec.of("folder-picker", FolderPickerConfig.class, FolderPickerConfig.DEFAULT,
            () -> new FolderPickerPlugin(System.err::println)));
  }
}
