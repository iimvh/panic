package panic.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import panic.chat.ChatConfig;
import panic.llm.Json;
import panic.llm.LlmConfig;
import panic.llm.Mistral;
import panic.llm.OpenRouter;
import panic.llm.ProviderConfig;

/**
 * Reads panic.json. Every string value may reference environment variables as {@code ${NAME}} (unset = empty),
 * so keys stay out of the file. Without a file, the config is built from the environment alone.
 */
public final class ConfigLoader {

  private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private final UnaryOperator<String> env;

  public ConfigLoader(UnaryOperator<String> env) {
    this.env = env;
  }

  public static ConfigLoader system() {
    return new ConfigLoader(System::getenv);
  }

  public PanicConfig load(Path file) throws IOException {
    if (!Files.exists(file)) return fromEnvironment();
    return parse(Files.readString(file));
  }

  public PanicConfig parse(String json) throws IOException {
    JsonNode tree = Json.MAPPER.readTree(json);
    if (tree == null || !tree.isObject()) throw new IOException("config must be a JSON object");
    return Json.MAPPER.treeToValue(interpolate(tree), PanicConfig.class);
  }

  /** OPENROUTER_API_KEY / MISTRAL_API_KEY; the first one set becomes the active provider, PANIC_MODEL its model. */
  public PanicConfig fromEnvironment() {
    Map<String, ProviderConfig> providers = new HashMap<>();
    String active = null;
    for (String id : new String[] {OpenRouter.ID, Mistral.ID}) {
      String key = env.apply(id.toUpperCase() + "_API_KEY");
      if (key != null && !key.isBlank()) {
        providers.put(id, new ProviderConfig(key, null));
        if (active == null) active = id;
      }
    }
    return new PanicConfig(providers, new LlmConfig(active, env.apply("PANIC_MODEL"), null, null),
        ChatConfig.DEFAULT);
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
