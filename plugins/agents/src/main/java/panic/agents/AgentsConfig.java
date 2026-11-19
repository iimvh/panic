package panic.agents;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The sub-agents the model may start, by name: {@code profiles = {"explore": {"description": "...", "tools":
 * ["read_file", "list_dir", "grep", "glob"]}}}. With {@code general} (default true) there is also {@code general}, which gets
 * every tool and the instructions the model writes for it.
 *
 * {@code maxDepth}: how deep sub-agents nest (default 1: a sub-agent cannot start another one).
 * {@code maxParallel}: how many sub-agents run at once in the whole app (default 4; more calls wait their turn).
 * {@code timeoutSeconds}: how long one sub-agent may run (default 600); it replaces the chat's tool timeout.
 */
public record AgentsConfig(Map<String, Profile> profiles, Boolean general, int maxDepth, int maxParallel,
    int timeoutSeconds) {

  public static final AgentsConfig DEFAULT = new AgentsConfig(null, null, 0, 0, 0);

  /** The name of the built-in profile whose instructions the model writes. */
  public static final String GENERAL = "general";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

  /**
   * One kind of sub-agent. {@code description} tells the model when to use it (required). {@code systemPrompt}
   * replaces the built-in sub-agent prompt (null = built in, with the description as its role). {@code tools}:
   * the only tools it may call (null = all). {@code maxSteps} / {@code maxTokens}: its limits (0 = the chat's).
   */
  public record Profile(String description, String systemPrompt, List<String> tools, int maxSteps, int maxTokens) {
    public Profile {
      if (description == null || description.isBlank()) {
        throw new IllegalArgumentException("a sub-agent profile needs a description");
      }
      description = description.strip();
      if (systemPrompt != null && systemPrompt.isBlank()) systemPrompt = null;
      if (tools != null) {
        if (tools.stream().anyMatch(t -> t == null || t.isBlank())) {
          throw new IllegalArgumentException("tool names must not be blank");
        }
        tools = List.copyOf(tools.stream().map(String::strip).distinct().sorted().toList());
      }
      if (maxSteps < 0) maxSteps = 0;
      if (maxTokens < 0) maxTokens = 0;
    }
  }

  public AgentsConfig {
    profiles = profiles == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(profiles));
    if (general == null) general = Boolean.TRUE;
    for (Map.Entry<String, Profile> e : profiles.entrySet()) {
      if (!NAME.matcher(e.getKey()).matches()) {
        throw new IllegalArgumentException("bad sub-agent name '" + e.getKey() + "': use [a-z][a-z0-9_-]*");
      }
      if (e.getValue() == null) throw new IllegalArgumentException("sub-agent '" + e.getKey() + "' has no profile");
      if (general && e.getKey().equals(GENERAL)) {
        throw new IllegalArgumentException("'" + GENERAL + "' is built in; turn it off with \"general\": false");
      }
    }
    if (maxDepth <= 0) maxDepth = 1;
    if (maxParallel <= 0) maxParallel = 4;
    if (timeoutSeconds <= 0) timeoutSeconds = 600;
  }

  /** True when there is any sub-agent to start. */
  public boolean any() {
    return general || !profiles.isEmpty();
  }
}
