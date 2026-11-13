package panic.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The permission policy. {@code risks}: the rule per {@link Risk} ({@code read}, {@code write}, {@code exec},
 * {@code network}), merged over the defaults (read allow, everything else ask). {@code tools}: rules for single
 * tools by name, which win over their risk's rule. A rule is {@code allow}, {@code ask} or {@code deny}.
 * {@code askTimeoutSeconds}: how long a call waits for the user before it is denied.
 *
 * Unknown risks or rules are a config error, not silently ignored: a typo must not open a hole.
 */
public record PermissionsConfig(Map<String, String> risks, Map<String, String> tools, int askTimeoutSeconds) {

  public static final PermissionsConfig DEFAULT = new PermissionsConfig(null, null, 0);

  public PermissionsConfig {
    Map<String, String> merged = new TreeMap<>();
    for (Risk r : Risk.values()) merged.put(r.wire(), Tools.Verdict.fallback(r).permission().wire());
    if (risks != null) {
      risks.forEach((risk, rule) -> merged.put(risk(risk).wire(), rule(risk, rule).wire()));
    }
    risks = Map.copyOf(merged);
    Map<String, String> perTool = new LinkedHashMap<>();
    if (tools != null) {
      tools.forEach((tool, rule) -> {
        if (tool == null || tool.isBlank()) throw new IllegalArgumentException("tools: empty tool name");
        perTool.put(tool.strip(), rule(tool, rule).wire());
      });
    }
    tools = Map.copyOf(perTool);
    if (askTimeoutSeconds <= 0) askTimeoutSeconds = Tools.Verdict.DEFAULT_ASK_TIMEOUT_SECONDS;
  }

  /** The rule for {@code tool} of {@code risk}: its own rule if it has one, else its risk's. */
  public Permission permission(String tool, Risk risk) {
    String rule = tools.get(tool);
    return Permission.of(rule != null ? rule : risks.get(risk.wire()));
  }

  private static Risk risk(String name) {
    try {
      return Risk.of(name);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("risks: unknown risk '" + name + "' (read, write, exec, network)");
    }
  }

  private static Permission rule(String key, String rule) {
    try {
      return Permission.of(rule);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException(key + ": unknown rule '" + rule + "' (allow, ask, deny)");
    }
  }
}
