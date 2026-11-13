package panic.llm;

/** Who wrote a message. Serialized as the lowercase name, the OpenAI wire format. */
public enum Role {
  SYSTEM, USER, ASSISTANT,
  /** The result of a tool call, answering an assistant message's {@link ToolCall} by id. */
  TOOL;

  public String wire() {
    return name().toLowerCase();
  }
}
