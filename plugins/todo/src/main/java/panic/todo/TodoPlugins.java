package panic.todo;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the agent's task list. */
public final class TodoPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("todo", TodoConfig.class, TodoConfig.DEFAULT, () -> new TodoPlugin(System.err::println)));
  }
}
