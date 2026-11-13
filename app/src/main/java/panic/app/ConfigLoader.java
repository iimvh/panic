package panic.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import panic.llm.Json;

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
  private static final String[] PROVIDERS = {"openrouter", "mistral"};

  private final UnaryOperator<String> env;

  public ConfigLoader(UnaryOperator<String> env) {
    this.env = env;
  }

  public static ConfigLoader system() {
    return new ConfigLoader(System::getenv);
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

  /** OPENROUTER_API_KEY / MISTRAL_API_KEY; the first one set becomes the active provider, PANIC_MODEL its model. */
  public ObjectNode fromEnvironment() {
    ObjectNode tree = Json.object();
    String active = null;
    for (String id : PROVIDERS) {
      String key = env.apply(id.toUpperCase() + "_API_KEY");
      if (key != null && !key.isBlank()) {
        tree.putObject(id).put("apiKey", key);
        if (active == null) active = id;
      }
    }
    ObjectNode llm = tree.putObject("llm");
    if (active != null) llm.put("provider", active);
    String model = env.apply("PANIC_MODEL");
    if (model != null && !model.isBlank()) llm.put("model", model);
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
