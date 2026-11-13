package panic.plugin;

import java.util.List;

/**
 * What a plugin module offers, found with {@link java.util.ServiceLoader}: implement it, and list the class
 * in {@code src/main/resources/META-INF/services/panic.plugin.PluginSet}.
 *
 * <pre>
 * public final class MemoryPlugins implements PluginSet {
 *   public List&lt;PluginSpec&lt;?&gt;&gt; plugins() {
 *     return List.of(PluginSpec.of("memory", MemoryConfig.class, MemoryConfig.DEFAULT, MemoryPlugin::new));
 *   }
 * }
 * </pre>
 */
public interface PluginSet {

  List<PluginSpec<?>> plugins();
}
