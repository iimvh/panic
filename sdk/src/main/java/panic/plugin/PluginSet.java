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

  /**
   * The panic.json sections this module derives from environment variables ({@code section -> field -> value}),
   * used only when there is no panic.json at all. {@code env} is the app's lookup (null when unset): the app is
   * the one place that reads the environment (rule 4), a plugin set only says which variables mean what. Order
   * matters: when several entries compete (for example, which provider is active) the earliest wins.
   */
  default java.util.Map<String, java.util.Map<String, String>> environment(
      java.util.function.UnaryOperator<String> env) {
    return java.util.Map.of();
  }
}
