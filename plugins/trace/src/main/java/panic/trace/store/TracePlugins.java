package panic.trace.store;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the trace store. */
public final class TracePlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("tracing", TracingConfig.class, TracingConfig.DEFAULT,
        () -> new TracingPlugin(System.err::println)));
  }
}
