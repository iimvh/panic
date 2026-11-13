package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a message {@code content} field. OpenAI sends a string; Mistral (and others) may send an array of
 * parts such as {@code [{"type":"text","text":"..."},{"type":"thinking",...}]}. Only text parts count.
 */
public final class Content {

  private Content() {
  }

  public static String text(JsonNode content) {
    if (content == null || content.isNull() || content.isMissingNode()) return "";
    if (content.isTextual()) return content.asText();
    if (content.isArray()) {
      StringBuilder out = new StringBuilder();
      for (JsonNode part : content) {
        if (part.isTextual()) {
          out.append(part.asText());
        } else if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
          out.append(part.path("text").asText());
        }
      }
      return out.toString();
    }
    return "";
  }
}
