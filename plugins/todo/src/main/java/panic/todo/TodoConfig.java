package panic.todo;

/**
 * {@code dir}: where task lists are stored, one JSON file per conversation, relative to the app's working
 * directory (null or blank = {@code .panic/todo}). {@code maxItems}: the most tasks one list may hold
 * (non-positive = 50).
 */
public record TodoConfig(String dir, int maxItems) {

  public static final TodoConfig DEFAULT = new TodoConfig(null, 0);

  public TodoConfig {
    dir = dir == null || dir.isBlank() ? ".panic/todo" : dir.strip();
    if (maxItems <= 0) maxItems = 50;
  }
}
