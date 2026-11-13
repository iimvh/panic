package panic.chat;

/**
 * {@code dir}: where conversations are stored, one JSON lines file each, relative to the app's working
 * directory (null or blank = {@code .panic/conversations}). {@code persist}: false keeps history in memory
 * only, gone at exit (null = true).
 */
public record ConversationsConfig(String dir, Boolean persist) {

  public static final String DEFAULT_DIR = ".panic/conversations";

  public static final ConversationsConfig DEFAULT = new ConversationsConfig(null, null);

  public ConversationsConfig {
    dir = dir == null || dir.isBlank() ? DEFAULT_DIR : dir.strip();
    if (persist == null) persist = Boolean.TRUE;
  }
}
