package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import panic.util.Json;

/**
 * Permission rules per tool and per command, after Claude Code's ({@code Bash(npm run *)}): a rule is
 * {@code tool} (every call of the tool) or {@code tool(specifier)}, where the specifier is matched against the
 * call's {@code command} argument (the {@code exec} tool). Rules are checked deny, then ask, then allow; the first
 * list with a match decides, and no match leaves the decision to the per-tool and per-risk rules.
 *
 * <ul>
 *   <li>{@code *} matches any text, spaces included; a specifier without one matches one exact command. A trailing
 *       {@code " *"} that is the only wildcard also matches the bare command ({@code ls *} matches {@code ls} and
 *       {@code ls -la}, not {@code lsof}); {@code ls:*} means the same.</li>
 *   <li>A compound command ({@code &&}, {@code ||}, {@code ;}, {@code |}, {@code |&}, {@code &}, newlines) is split.
 *       Deny and ask apply when any part matches, including commands inside {@code $(...)}, backticks or a
 *       {@code (...)} subshell. Allow applies only when every part matches an allow rule; a command with a
 *       substitution or a subshell, or one that does not parse (an open quote, a trailing operator), is never
 *       allowed by a rule.</li>
 *   <li>Wrappers are looked through: {@code timeout}, {@code time}, {@code nice}, {@code nohup}, {@code stdbuf},
 *       {@code command}, {@code builtin}, {@code noglob} and a bare {@code xargs}. Deny and ask also look past
 *       leading variable assignments ({@code FOO=1 cmd}); allow does not.</li>
 *   <li>Tool names may end in {@code *} ({@code mcp__chrome-devtools__*}); {@code mcp__<server>} names every tool
 *       of that server. An allow rule's wildcard must follow a literal {@code mcp__<server>__}, so no allow rule
 *       opens every tool.</li>
 * </ul>
 *
 * Rules only read the command text: {@code exec(kill *)} does not stop {@code /bin/kill}. The hard limit stays
 * {@code shell.deny}.
 */
final class PermissionRules {

  /** One parsed rule: the tool part and the specifier (null for the whole tool). */
  record Rule(String text, String tool, String specifier) {

    /** Whether this rule names {@code name}. */
    boolean names(String name) {
      if (tool.endsWith("*")) return name.startsWith(tool.substring(0, tool.length() - 1));
      if (tool.equals(name)) return true;
      // mcp__<server> covers mcp__<server>__<tool>
      return tool.startsWith("mcp__") && tool.indexOf("__", 5) < 0 && name.startsWith(tool + "__");
    }
  }

  /** The rule that decided, and how. */
  record Match(Permission permission, Rule rule) {
  }

  private static final Pattern RULE = Pattern.compile("([A-Za-z0-9_*-]+)(?:\\((.*)\\))?");
  private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=\\S*");

  private final List<Rule> deny;
  private final List<Rule> ask;
  private final List<Rule> allow;

  PermissionRules(List<String> deny, List<String> ask, List<String> allow) {
    this.deny = parse("deny", deny, false);
    this.ask = parse("ask", ask, false);
    this.allow = parse("allow", allow, true);
  }

  private static List<Rule> parse(String list, List<String> rules, boolean allow) {
    List<Rule> out = new ArrayList<>();
    if (rules == null) return out;
    for (String text : rules) {
      if (text == null || text.isBlank()) throw new IllegalArgumentException("rules." + list + ": empty rule");
      var m = RULE.matcher(text.strip());
      if (!m.matches()) {
        throw new IllegalArgumentException("rules." + list + ": bad rule '" + text + "' (tool or tool(command *))");
      }
      String tool = m.group(1);
      String spec = m.group(2);
      if (tool.indexOf('*') >= 0 && tool.indexOf('*') != tool.length() - 1) {
        throw new IllegalArgumentException("rules." + list + ": '" + text + "': * may only end a tool name");
      }
      if (allow && tool.endsWith("*") && !(tool.startsWith("mcp__") && tool.indexOf("__", 5) > 0)) {
        throw new IllegalArgumentException("rules.allow: '" + text + "' would allow tools by a wildcard; only "
            + "mcp__<server>__* style wildcards may allow");
      }
      if (spec != null) {
        spec = spec.strip();
        if (spec.isEmpty()) throw new IllegalArgumentException("rules." + list + ": '" + text + "' has an empty ()");
        if (spec.endsWith(":*")) spec = spec.substring(0, spec.length() - 2) + " *";
        if (spec.equals("*")) spec = null; // tool(*) is the whole tool
      }
      out.add(new Rule(text.strip(), tool, spec));
    }
    return List.copyOf(out);
  }

  boolean isEmpty() {
    return deny.isEmpty() && ask.isEmpty() && allow.isEmpty();
  }

  /** The decision of the rules for a call of {@code tool} with raw JSON {@code arguments}; null when none applies. */
  Match decide(String tool, String arguments) {
    if (isEmpty()) return null;
    String command = command(arguments);
    Commands parsed = command == null ? null : split(command);
    for (Rule r : deny) {
      if (matchesAny(r, tool, command, parsed)) return new Match(Permission.DENY, r);
    }
    for (Rule r : ask) {
      if (matchesAny(r, tool, command, parsed)) return new Match(Permission.ASK, r);
    }
    // allow: every part of the command must be allowed (by any allow rule), and the command must parse
    List<Rule> named = allow.stream().filter(r -> r.names(tool)).toList();
    if (named.isEmpty()) return null;
    for (Rule r : named) {
      if (r.specifier() == null) return new Match(Permission.ALLOW, r);
    }
    if (parsed == null || !parsed.clean()) return null;
    Rule last = null;
    for (String part : parsed.forAllow()) {
      Rule hit = null;
      for (Rule r : named) {
        if (glob(r.specifier(), part)) {
          hit = r;
          break;
        }
      }
      if (hit == null) return null;
      last = hit;
    }
    return last == null ? null : new Match(Permission.ALLOW, last);
  }

  /** Deny and ask: the whole tool, or any part of the command (substitutions and subshells included). */
  private static boolean matchesAny(Rule r, String tool, String command, Commands parsed) {
    if (!r.names(tool)) return false;
    if (r.specifier() == null) return true;
    if (command == null) return false;
    for (String part : parsed.forDeny()) {
      if (glob(r.specifier(), part)) return true;
    }
    return false;
  }

  private static String command(String arguments) {
    if (arguments == null) return null;
    JsonNode args = Json.parseOrNull(arguments);
    if (args == null || !args.path("command").isTextual()) return null;
    return args.path("command").asText();
  }

  /** Whether {@code command} matches {@code pattern} (see the class comment). */
  static boolean glob(String pattern, String command) {
    String text = normalize(command);
    String p = normalize(pattern);
    int stars = p.length() - p.replace("*", "").length();
    if (stars == 1 && p.endsWith(" *")) {
      String prefix = p.substring(0, p.length() - 2);
      return text.equals(prefix) || text.startsWith(prefix + " ");
    }
    StringBuilder regex = new StringBuilder();
    for (String piece : p.split("\\*", -1)) {
      if (!regex.isEmpty()) regex.append(".*");
      regex.append(Pattern.quote(piece));
    }
    return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(text).matches();
  }

  private static String normalize(String s) {
    return s.strip().replaceAll("[ \\t]+", " ");
  }

  /**
   * A command split into its simple commands. {@code top}: the parts at the top level; {@code nested}: those
   * inside substitutions and subshells; {@code clean}: it parsed and has nothing nested, so allow rules may apply.
   */
  record Commands(List<String> top, List<String> nested, boolean clean) {

    /** Every part, top and nested. */
    List<String> all() {
      List<String> out = new ArrayList<>(top);
      out.addAll(nested);
      return out;
    }

    /** What deny and ask rules look at: every part, past assignments and wrappers, and also as written. */
    List<String> forDeny() {
      List<String> out = new ArrayList<>();
      for (String part : top) {
        out.add(part);
        out.add(unwrap(part, true));
      }
      for (String part : nested) {
        out.add(part);
        out.add(unwrap(part, true));
      }
      return out;
    }

    /** What allow rules must all match: each top part past its wrappers (one with an assignment matches none). */
    List<String> forAllow() {
      List<String> out = new ArrayList<>();
      for (String part : top) out.add(unwrap(part, false));
      return out;
    }
  }

  /** Splits {@code command} at unquoted operators and newlines, and pulls out what is nested. */
  static Commands split(String command) {
    List<String> top = new ArrayList<>();
    List<String> nested = new ArrayList<>();
    boolean clean = true;
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    int n = command.length();
    boolean pendingOperator = false;
    for (int i = 0; i < n; i++) {
      char c = command.charAt(i);
      if (quote == '\'') {
        cur.append(c);
        if (c == '\'') quote = 0;
        continue;
      }
      if (c == '\\' && i + 1 < n) {
        cur.append(c).append(command.charAt(++i));
        continue;
      }
      if (quote == '"') {
        if (c == '"') quote = 0;
        else if (c == '`' || (c == '$' && i + 1 < n && command.charAt(i + 1) == '(')) {
          int end = closing(command, i);
          if (end < 0) return new Commands(top, nested, false);
          nested.addAll(split(inner(command, i, end)).all());
          clean = false;
          cur.append(command, i, end + 1);
          i = end;
          continue;
        }
        cur.append(c);
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        cur.append(c);
        continue;
      }
      if (c == '`' || (c == '$' && i + 1 < n && command.charAt(i + 1) == '(') || c == '(') {
        int end = closing(command, i);
        if (end < 0) return new Commands(withPart(top, cur), nested, false);
        nested.addAll(split(inner(command, i, end)).all());
        clean = false;
        cur.append(command, i, end + 1);
        i = end;
        continue;
      }
      boolean redirect = i > 0 && (command.charAt(i - 1) == '>' || command.charAt(i - 1) == '<');
      if (c == '\n' || c == ';') {
        addPart(top, cur);
        pendingOperator = c == ';' ? false : pendingOperator;
        continue;
      }
      if (c == '&' && !redirect && !(i + 1 < n && command.charAt(i + 1) == '>')) {
        if (i + 1 < n && command.charAt(i + 1) == '&') i++;
        if (!addPart(top, cur)) clean = false; // "&& x" with nothing before
        pendingOperator = true;
        continue;
      }
      if (c == '|' && !redirect) {
        if (i + 1 < n && (command.charAt(i + 1) == '|' || command.charAt(i + 1) == '&')) i++;
        if (!addPart(top, cur)) clean = false;
        pendingOperator = true;
        continue;
      }
      if (!Character.isWhitespace(c)) pendingOperator = false;
      cur.append(c);
    }
    if (quote != 0) clean = false; // an open quote
    if (pendingOperator && cur.toString().isBlank()) clean = false; // "npm test &&"
    addPart(top, cur);
    return new Commands(List.copyOf(top), List.copyOf(nested), clean);
  }

  private static List<String> withPart(List<String> top, StringBuilder cur) {
    List<String> out = new ArrayList<>(top);
    addPart(out, cur);
    return out;
  }

  /** Adds the part being read, if any; false when it was empty. */
  private static boolean addPart(List<String> parts, StringBuilder cur) {
    String part = cur.toString().strip();
    cur.setLength(0);
    if (part.isEmpty()) return false;
    parts.add(part);
    return true;
  }

  /** The index of what closes the substitution or subshell opened at {@code from}; -1 when it is not closed. */
  private static int closing(String s, int from) {
    if (s.charAt(from) == '`') return s.indexOf('`', from + 1);
    int start = s.charAt(from) == '$' ? from + 2 : from + 1;
    int depth = 1;
    char quote = 0;
    for (int i = start; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
        else if (c == '\\' && quote == '"') i++;
        continue;
      }
      if (c == '\\') {
        i++;
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static String inner(String s, int from, int end) {
    int start = s.charAt(from) == '`' ? from + 1 : s.charAt(from) == '$' ? from + 2 : from + 1;
    return s.substring(start, end);
  }

  private static final Set<String> SIMPLE_WRAPPERS = Set.of("time", "nohup", "builtin", "noglob");

  /**
   * {@code part} without leading wrappers ({@code timeout 30 npm test} is {@code npm test}). With
   * {@code pastAssignments}, leading {@code VAR=value} words go too; otherwise a part that starts with one is
   * returned with a marker no allow rule can match.
   */
  static String unwrap(String part, boolean pastAssignments) {
    List<String> words = new ArrayList<>(List.of(normalize(part).split(" ")));
    boolean changed = true;
    while (changed && !words.isEmpty()) {
      changed = false;
      String w = words.get(0);
      if (ASSIGNMENT.matcher(w).matches()) {
        if (!pastAssignments) return "\u0000" + part; // allow rules do not look past an assignment
        words.remove(0);
        changed = true;
      } else if (SIMPLE_WRAPPERS.contains(w)) {
        words.remove(0);
        if (w.equals("time") && !words.isEmpty() && words.get(0).equals("-p")) words.remove(0);
        changed = true;
      } else if (w.equals("command")) {
        if (words.size() > 1 && (words.get(1).equals("-v") || words.get(1).equals("-V"))) break;
        words.remove(0);
        changed = true;
      } else if (w.equals("timeout")) {
        words.remove(0);
        while (!words.isEmpty() && words.get(0).startsWith("-")) {
          String opt = words.remove(0);
          if ((opt.equals("-s") || opt.equals("-k") || opt.equals("--signal") || opt.equals("--kill-after"))
              && !words.isEmpty()) {
            words.remove(0);
          }
        }
        if (!words.isEmpty()) words.remove(0); // the duration
        changed = true;
      } else if (w.equals("nice")) {
        words.remove(0);
        while (!words.isEmpty() && words.get(0).startsWith("-")) {
          String opt = words.remove(0);
          if (opt.equals("-n") && !words.isEmpty()) words.remove(0);
        }
        changed = true;
      } else if (w.equals("stdbuf")) {
        words.remove(0);
        while (!words.isEmpty() && words.get(0).startsWith("-")) {
          String opt = words.remove(0);
          if (opt.length() == 2 && !words.isEmpty()) words.remove(0); // -o L
        }
        changed = true;
      } else if (w.equals("xargs") && words.size() > 1 && !words.get(1).startsWith("-")) {
        words.remove(0); // only a bare xargs: its options change what runs
        changed = true;
      }
    }
    return String.join(" ", words);
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "deny=%s ask=%s allow=%s", texts(deny), texts(ask), texts(allow));
  }

  private static List<String> texts(List<Rule> rules) {
    return rules.stream().map(Rule::text).toList();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof PermissionRules other && deny.equals(other.deny) && ask.equals(other.ask)
        && allow.equals(other.allow);
  }

  @Override
  public int hashCode() {
    return Objects.hash(deny, ask, allow);
  }
}
