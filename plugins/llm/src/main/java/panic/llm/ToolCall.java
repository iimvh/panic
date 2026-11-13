package panic.llm;

import java.util.Objects;

/**
 * The model asking to run one tool. {@code arguments} is the raw JSON text the model wrote: it may be invalid
 * and is checked by whoever runs the tool, so a bad call can be reported back to the model instead of failing
 * the request. {@code id} pairs the call with its result message ({@link Message#tool}).
 */
public record ToolCall(String id, String name, String arguments) {

  public ToolCall {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    if (arguments == null || arguments.isBlank()) arguments = "{}";
  }
}
