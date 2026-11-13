package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A tool the model may call: a name, what it does (the model reads this to decide), and a JSON Schema for
 * its arguments. Sent as an OpenAI {@code {"type":"function","function":{...}}} entry.
 */
public record ToolSpec(String name, String description, JsonNode parameters) {

  /** The name rule shared by OpenAI-compatible providers. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  public ToolSpec {
    Objects.requireNonNull(name, "name");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("tool name must match " + NAME.pattern() + ": " + name);
    }
    if (description == null) description = "";
    if (parameters == null || parameters.isNull() || parameters.isMissingNode()) {
      parameters = Json.object().put("type", "object").set("properties", Json.object());
    }
    if (!parameters.isObject()) throw new IllegalArgumentException("tool parameters must be a JSON object");
  }
}
