package panic.llm;

import java.util.Objects;

/**
 * Whether the model must, may or must not call a tool. {@code mode} is {@code auto}, {@code none},
 * {@code required}, or {@code function} with the {@code name} it must call.
 */
public record ToolChoice(String mode, String name) {

  public static final ToolChoice AUTO = new ToolChoice("auto", null);
  public static final ToolChoice NONE = new ToolChoice("none", null);
  public static final ToolChoice REQUIRED = new ToolChoice("required", null);

  public ToolChoice {
    Objects.requireNonNull(mode, "mode");
    switch (mode) {
      case "auto", "none", "required" -> {
        if (name != null) throw new IllegalArgumentException("tool choice '" + mode + "' takes no name");
      }
      case "function" -> Objects.requireNonNull(name, "name");
      default -> throw new IllegalArgumentException("unknown tool choice: " + mode);
    }
  }

  /** The model must call this tool. */
  public static ToolChoice named(String name) {
    return new ToolChoice("function", name);
  }
}
