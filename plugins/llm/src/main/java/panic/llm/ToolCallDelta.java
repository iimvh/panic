package panic.llm;

/**
 * A piece of a tool call while the model is still writing it, so a front-end can show a long call (a file
 * being written) before it runs. {@code index} numbers the calls of one reply from 0; {@code id} and
 * {@code name} are set on the piece that brings them and null after; {@code arguments} is only the new
 * text, never the whole. The finished calls come with the {@link Completion}; these pieces are only progress.
 */
public record ToolCallDelta(int index, String id, String name, String arguments) {

  public ToolCallDelta {
    if (index < 0) throw new IllegalArgumentException("index < 0: " + index);
    if (arguments == null) arguments = "";
  }
}
