package panic.web;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the web front-end. */
public final class WebPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("web", WebConfig.class, WebConfig.DEFAULT, () -> new WebPlugin(System.err::println)));
  }
}
