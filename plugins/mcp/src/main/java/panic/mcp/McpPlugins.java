package panic.mcp;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: tools from Model Context Protocol servers. */
public final class McpPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("mcp", McpConfig.class, McpConfig.DEFAULT, () -> new McpPlugin(System.err::println)));
  }
}
