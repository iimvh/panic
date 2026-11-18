package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a message {@code content} field. OpenAI sends a string; Mistral (and others) may send an array of
 * parts such as {@code [{"type":"text","text":"..."},{"type":"thinking",...}]}. {@link #text} keeps the text
 * parts; {@link #reasoning} reads what the model thought, wherever the provider puts it.
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

  /**
   * The reasoning in a message or stream delta: {@code reasoning} (OpenRouter, Ollama), else
   * {@code reasoning_content} (DeepSeek, vLLM, LM Studio; some servers send both with the same text), plus the
   * {@code thinking} parts of {@code content} (Mistral). Empty when there is none.
   */
  public static String reasoning(JsonNode message) {
    if (message == null || !message.isObject()) return "";
    StringBuilder out = new StringBuilder();
    JsonNode field = message.path("reasoning");
    if (!field.isTextual() || field.asText().isEmpty()) field = message.path("reasoning_content");
    if (field.isTextual()) out.append(field.asText());
    JsonNode content = message.path("content");
    if (content.isArray()) {
      for (JsonNode part : content) {
        if ("thinking".equals(part.path("type").asText())) out.append(text(part.path("thinking")));
      }
    }
    return out.toString();
  }
}
