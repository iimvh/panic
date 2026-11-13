package panic.llm;

/** Who wrote a message. Serialized as the lowercase name, the OpenAI wire format. */
public enum Role {
  SYSTEM, USER, ASSISTANT;

  public String wire() {
    return name().toLowerCase();
  }
}
