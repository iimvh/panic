package panic.memory;

import java.util.List;

/**
 * {@code enabled}: false adds no instructions (null = true). {@code global}: the user's own instruction file,
 * sent to every chat, relative to the app's working directory (null or blank = {@code .panic/AGENTS.md}).
 * {@code names}: the file names looked for in each folder, the first one found wins (null or empty =
 * {@code AGENTS.md}, then {@code CLAUDE.md}). {@code root}: the folder of a chat without a project (null or blank =
 * {@code .}). {@code maxChars}: the most characters the section may hold (non-positive = 12000).
 */
public record InstructionsConfig(Boolean enabled, String global, List<String> names, String root, int maxChars) {

  public static final InstructionsConfig DEFAULT = new InstructionsConfig(null, null, null, null, 0);

  public InstructionsConfig {
    if (enabled == null) enabled = true;
    global = global == null || global.isBlank() ? ".panic/AGENTS.md" : global.strip();
    names = names == null ? List.of() : names.stream().filter(n -> n != null && !n.isBlank()).map(String::strip)
        .toList();
    if (names.isEmpty()) names = List.of("AGENTS.md", "CLAUDE.md");
    root = root == null || root.isBlank() ? "." : root.strip();
    if (maxChars <= 0) maxChars = 12_000;
  }
}
