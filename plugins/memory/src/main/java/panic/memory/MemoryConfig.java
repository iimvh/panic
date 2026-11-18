package panic.memory;

/**
 * {@code enabled}: false offers no memory (null = true). {@code dir}: where memories are stored, relative to the
 * app's working directory (null or blank = {@code .panic/memory}). {@code root}: the folder of a chat without a
 * project (null or blank = {@code .}). {@code maxIndexChars}: the most characters of each {@code MEMORY.md} sent in
 * the system prompt (non-positive = 6000). {@code maxFileChars}: the largest memory file (non-positive = 20000).
 */
public record MemoryConfig(Boolean enabled, String dir, String root, int maxIndexChars, int maxFileChars) {

  public static final MemoryConfig DEFAULT = new MemoryConfig(null, null, null, 0, 0);

  public MemoryConfig {
    if (enabled == null) enabled = true;
    dir = dir == null || dir.isBlank() ? ".panic/memory" : dir.strip();
    root = root == null || root.isBlank() ? "." : root.strip();
    if (maxIndexChars <= 0) maxIndexChars = 6_000;
    if (maxFileChars <= 0) maxFileChars = 20_000;
  }
}
