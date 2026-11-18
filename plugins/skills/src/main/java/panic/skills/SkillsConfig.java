package panic.skills;

import java.util.List;

/**
 * {@code enabled}: false offers no skills (null = true). {@code global}: the user's own skills folder, for every
 * chat, relative to the app's working directory (null or blank = {@code .panic/skills}). {@code dirs}: the skills
 * folders looked for in a chat's project, the first one wins on a name found twice (null or empty =
 * {@code .panic/skills}, {@code .agents/skills}, {@code .claude/skills}). {@code root}: the folder of a chat without
 * a project (null or blank = {@code .}). {@code maxListChars}: the most characters the list in the system prompt
 * may hold (non-positive = 6000). {@code maxSkillChars}: the most characters of a skill or one of its files given
 * to the model (non-positive = 40000).
 */
public record SkillsConfig(Boolean enabled, String global, List<String> dirs, String root, int maxListChars,
    int maxSkillChars) {

  public static final SkillsConfig DEFAULT = new SkillsConfig(null, null, null, null, 0, 0);

  public SkillsConfig {
    if (enabled == null) enabled = true;
    global = global == null || global.isBlank() ? ".panic/skills" : global.strip();
    dirs = dirs == null ? List.of() : dirs.stream().filter(d -> d != null && !d.isBlank()).map(String::strip)
        .toList();
    if (dirs.isEmpty()) dirs = List.of(".panic/skills", ".agents/skills", ".claude/skills");
    root = root == null || root.isBlank() ? "." : root.strip();
    if (maxListChars <= 0) maxListChars = 6_000;
    if (maxSkillChars <= 0) maxSkillChars = 40_000;
  }
}
