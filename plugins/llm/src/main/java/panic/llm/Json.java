package panic.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The one ObjectMapper of panic: lenient on unknown fields, since providers keep adding them. */
public final class Json {

  public static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Json() {
  }

  public static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  /** Parses JSON text; null when it is not JSON. */
  public static JsonNode parseOrNull(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      return MAPPER.readTree(text);
    } catch (java.io.IOException e) {
      return null;
    }
  }
}
