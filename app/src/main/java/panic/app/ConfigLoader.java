package panic.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import panic.plugin.PluginSet;
import panic.util.Json;

/**
 * Reads panic.json: one section per plugin, by plugin name. Every string may reference environment
 * variables as {@code ${NAME}} (unset = empty), so keys stay out of the file. Without a file, the config is
 * built from the environment alone.
 *
 * The older layout {@code "providers": {"openrouter": {...}}} is still read: its entries become top-level
 * sections.
 */
public final class ConfigLoader {

  private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private final UnaryOperator<String> env;
  private final Collection<? extends PluginSet> sets;

  /** {@code sets}: the plugin modules asked what the environment means when there is no file. */
  public ConfigLoader(UnaryOperator<String> env, Collection<? extends PluginSet> sets) {
    this.env = env;
    this.sets = List.copyOf(sets);
  }

  /** A loader that reads the real environment, for the plugin modules found on the classpath. */
  public static ConfigLoader system() {
    return new ConfigLoader(System::getenv, Panic.discoverSets());
  }

  public ObjectNode load(Path file) throws IOException {
    if (!Files.exists(file)) return fromEnvironment();
    return parse(Files.readString(file));
  }

  public ObjectNode parse(String json) throws IOException {
    JsonNode tree = Json.MAPPER.readTree(json);
    if (!(tree instanceof ObjectNode obj)) throw new IOException("config must be a JSON object");
    interpolate(obj);
    if (obj.remove("providers") instanceof ObjectNode legacy) {
      legacy.properties().forEach(p -> {
        if (!obj.has(p.getKey())) obj.set(p.getKey(), p.getValue());
      });
    }
    return obj;
  }

  /**
   * The config when there is no file: each plugin module says which environment variables fill which sections
   * ({@link PluginSet#environment}); the loader only reads them through its lookup and merges the answers.
   */
  public ObjectNode fromEnvironment() {
    ObjectNode tree = Json.object();
    for (PluginSet set : sets) {
      set.environment(env).forEach((section, fields) -> {
        ObjectNode node = tree.has(section) ? (ObjectNode) tree.get(section) : tree.putObject(section);
        fields.forEach((k, v) -> {
          if (!node.has(k)) node.put(k, v);
        });
      });
    }
    return tree;
  }

  private JsonNode interpolate(JsonNode node) {
    if (node.isTextual()) return TextNode.valueOf(substitute(node.asText()));
    if (node instanceof ObjectNode obj) {
      for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        e.setValue(interpolate(e.getValue()));
      }
    } else if (node instanceof ArrayNode arr) {
      for (int i = 0; i < arr.size(); i++) arr.set(i, interpolate(arr.get(i)));
    }
    return node;
  }

  private String substitute(String text) {
    Matcher m = VAR.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = env.apply(m.group(1));
      m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
