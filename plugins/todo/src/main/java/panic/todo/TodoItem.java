package panic.todo;

import java.util.Locale;
import java.util.Objects;

/** One entry of a task list. */
public record TodoItem(String text, Status status) {

  /** Where a task stands. Written in lowercase ({@code in_progress}) in tool arguments and files. */
  public enum Status {
    PENDING("[ ]"), IN_PROGRESS("[~]"), DONE("[x]");

    private final String mark;

    Status(String mark) {
      this.mark = mark;
    }

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /** The status named {@code name} (any case). */
    public static Status of(String name) {
      return valueOf(name.strip().toUpperCase(Locale.ROOT));
    }
  }

  public TodoItem {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(status, "status");
    text = text.strip();
    if (text.isEmpty()) throw new IllegalArgumentException("a task needs a text");
  }

  /** {@code [x] text}, as the model and the user see it. */
  public String line() {
    return status.mark + " " + text;
  }
}
