package panic.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigHandle;
import context.ConfigPlugin;
import context.Root;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import panic.util.Json;
import panic.plugin.Configs;
import panic.plugin.NoConfig;
import panic.plugin.PluginSpec;

/**
 * Installs every PluginSpec under the root, each with the panic.json section of its name, and keeps them in
 * step with the file. Also the {@link Configs} service, for plugins that change other plugins' configs.
 *
 * <ul>
 *   <li>missing section: the spec's default config; {@code false}: not installed;</li>
 *   <li>on reload, only plugins whose SECTION changed are touched (so a runtime change made through
 *       Configs survives an edit of another section);</li>
 *   <li>{@code overrides} (command-line flags such as --web) are merged over the file, field by field.</li>
 * </ul>
 */
final class PluginHost implements Configs {

  private static final class Entry {
    final PluginSpec<?> spec;
    ConfigHandle<Object> handle;
    JsonNode section;

    Entry(PluginSpec<?> spec) {
      this.spec = spec;
    }
  }

  private final Root root;
  private final ObjectNode overrides;
  private final Consumer<String> warn;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  PluginHost(Root root, List<PluginSpec<?>> specs, ObjectNode overrides, Consumer<String> warn) {
    this.root = root;
    this.overrides = overrides;
    this.warn = warn;
    for (PluginSpec<?> spec : specs) {
      if (entries.putIfAbsent(spec.name(), new Entry(spec)) != null) {
        throw new IllegalStateException("two plugins are named '" + spec.name() + "'");
      }
    }
  }

  List<String> names() {
    return List.copyOf(entries.keySet());
  }

  /** First install, in spec order. A plugin that fails to apply now throws here. */
  synchronized void start(ObjectNode file) {
    ObjectNode merged = merge(file);
    warnUnknown(merged);
    for (Entry e : entries.values()) {
      e.section = merged.get(e.spec.name());
      if (enabled(e.section)) e.handle = install(e.spec, e.section);
    }
  }

  /** Applies a reloaded file. Collects failures so one bad section does not block the others. */
  synchronized void apply(ObjectNode file) {
    ObjectNode merged = merge(file);
    warnUnknown(merged);
    List<String> failures = new ArrayList<>();
    for (Entry e : entries.values()) {
      JsonNode next = merged.get(e.spec.name());
      if (same(next, e.section)) continue;
      e.section = next;
      try {
        if (!enabled(next)) {
          if (e.handle != null) e.handle.dispose();
          e.handle = null;
        } else if (e.handle == null) {
          e.handle = install(e.spec, next);
        } else {
          e.handle.update(convert(e.spec, next));
        }
      } catch (RuntimeException ex) {
        failures.add(e.spec.name() + ": " + ex.getMessage());
      }
    }
    if (!failures.isEmpty()) throw new IllegalStateException(String.join("; ", failures));
  }

  // ---------------- Configs ----------------

  @Override
  public <C> C get(String plugin, Class<C> type) {
    return type.cast(installed(plugin).handle.config());
  }

  @Override
  public <C> void update(String plugin, C config) {
    Entry e = installed(plugin);
    e.handle.update(e.spec.configType().cast(config));
  }

  private Entry installed(String plugin) {
    Entry e = entries.get(plugin);
    if (e == null || e.handle == null) throw new IllegalArgumentException("plugin '" + plugin + "' is not installed");
    return e;
  }

  // ---------------- internals ----------------

  @SuppressWarnings("unchecked")
  private ConfigHandle<Object> install(PluginSpec<?> spec, JsonNode section) {
    Object config = convert(spec, section);
    ConfigPlugin<Object> plugin = (ConfigPlugin<Object>) spec.factory().get();
    return root.plugin(spec.name(), plugin, config);
  }

  private static Object convert(PluginSpec<?> spec, JsonNode section) {
    if (spec.configType() == NoConfig.class) return NoConfig.INSTANCE;
    if (section == null || section.isNull() || section.isBoolean()) return spec.defaultConfig();
    try {
      return Json.MAPPER.treeToValue(section, spec.configType());
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("bad config for '" + spec.name() + "': " + e.getOriginalMessage(), e);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("bad config for '" + spec.name() + "': " + e.getMessage(), e);
    }
  }

  private static boolean enabled(JsonNode section) {
    return section == null || !(section.isBoolean() && !section.asBoolean());
  }

  private static boolean same(JsonNode a, JsonNode b) {
    return a == null ? b == null : a.equals(b);
  }

  /** The file with each override section merged in field by field (an override turns false into {}). */
  private ObjectNode merge(ObjectNode file) {
    ObjectNode out = file.deepCopy();
    overrides.properties().forEach(o -> {
      JsonNode base = out.get(o.getKey());
      ObjectNode section = base instanceof ObjectNode obj ? obj : Json.object();
      if (o.getValue() instanceof ObjectNode fields) section.setAll(fields);
      out.set(o.getKey(), section);
    });
    return out;
  }

  private void warnUnknown(ObjectNode merged) {
    merged.fieldNames().forEachRemaining(name -> {
      if (!entries.containsKey(name)) {
        warn.accept("[config] no plugin named '" + name + "' (installed: " + entries.keySet() + ")");
      }
    });
  }
}
