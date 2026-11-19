package panic.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The outline of some lines of a text file: its imports (collapsed), declarations (types, functions, methods,
 * top-level constants), headings and top-level CSS rules, each with its line number. It stands in for a file's
 * content once that no longer fits the context: the model keeps a map of the file and can read back exactly the part
 * it needs (offset/limit) instead of the whole file.
 *
 * Deterministic and language-agnostic on purpose (no parser, no model call): a few patterns that hold for the
 * C family, JavaScript/TypeScript/JSX, Python, Go, Rust, Kotlin, Markdown, CSS and JSON. A file they do not fit
 * gets its first lines. Never longer than about a fifth of the content (at least {@link #MIN_BUDGET} chars).
 */
final class Outline {

  /** Below this, the content is small enough that an outline saves too little to be worth it. */
  static final int MIN_CONTENT = 1_500;
  static final int MIN_BUDGET = 600;
  private static final int LINE_CHARS = 120;

  private static final Pattern IMPORT = Pattern.compile(
      "^\\s*(?:import\\b|from\\s+\\S+\\s+import\\b|#include\\b|use\\s+[\\w:{]|require\\(|package\\s+[\\w.]+;?\\s*$)");
  private static final String MODIFIERS =
      "(?:(?:export|default|async|public|private|protected|internal|static|final|abstract|sealed|override|open|"
          + "data|inline|synchronized|pub(?:\\([^)]*\\))?|unsafe)\\s+)*";
  /** Declarations introduced by a keyword, at any depth up to two levels. */
  private static final Pattern DECLARATION = Pattern.compile("^(?:\\s{0,8}|\\t{0,2})" + MODIFIERS
      + "(?:function\\*?|class|interface|enum|record|struct|trait|impl|module|namespace|object|def|fn|func|type)\\s");
  /** Top-level bindings only (inside a body they are noise): const/let/var, and export default. */
  private static final Pattern BINDING = Pattern.compile("^(?:\\s{0,2})" + MODIFIERS + "(?:const|let|var|val)\\s+\\w+"
      + "|^export\\s+default\\b");
  /** A method of the C family: modifiers, a type, a name and an open parenthesis, at most one level deep. */
  private static final Pattern METHOD = Pattern.compile(
      "^(?:\\s{0,4}|\\t?)(?:(?:public|private|protected|static|final|abstract|synchronized|default|override)\\s+)+"
          + "[\\w<>\\[\\],.?]+(?:\\s+[\\w<>\\[\\],.?]+)*\\s+\\w+\\s*\\(");
  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+\\S");
  private static final Pattern CSS_RULE = Pattern.compile("^(?:[^\\s{}/*][^{};]*\\{\\s*|@[\\w-]+[^;]*\\{?\\s*)$");
  private static final Pattern JSON_KEY = Pattern.compile("^\\s{0,2}\"[^\"]+\"\\s*:");

  private Outline() {
  }

  /**
   * The outline of {@code lines} (the file's lines {@code first}..), or null when the content is too small for one
   * to be worth it. {@code path} and {@code total} (the file's line count) go in its first line.
   */
  static String of(String path, List<String> lines, int first, int total, int contentChars) {
    if (contentChars < MIN_CONTENT || lines.isEmpty()) return null;
    int budget = Math.max(MIN_BUDGET, contentChars / 5);
    List<int[]> picked = new ArrayList<>(); // {line index, indent}
    int importStart = -1;
    int importEnd = -1;
    int imports = 0;
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String text = lines.get(i);
      if (IMPORT.matcher(text).find()) {
        if (importStart < 0) importStart = i;
        importEnd = i;
        imports++;
        continue;
      }
      if (imports > 0 && !text.isBlank()) {
        rows.add(importRow(first + importStart, first + importEnd, imports));
        picked.add(new int[] {-1, 0});
        importStart = -1;
        imports = 0;
      }
      if (matches(text)) {
        picked.add(new int[] {i, indent(text)});
        rows.add(row(first + i, text));
      }
    }
    if (imports > 0) {
      rows.add(importRow(first + importStart, first + importEnd, imports));
      picked.add(new int[] {-1, 0});
    }
    String header = "outline of " + path + ", lines " + first + "-" + (first + lines.size() - 1) + " of " + total
        + " (only declarations are kept; read_file with offset/limit shows the full text):\n";
    if (rows.isEmpty()) return header + firstLines(lines, first, budget);
    // too long: keep the shallowest levels first
    int maxIndent = picked.stream().mapToInt(p -> p[1]).max().orElse(0);
    while (length(rows) > budget && maxIndent > 0) {
      int cut = maxIndent;
      List<String> keptRows = new ArrayList<>();
      List<int[]> keptPicked = new ArrayList<>();
      for (int k = 0; k < rows.size(); k++) {
        if (picked.get(k)[1] < cut) {
          keptRows.add(rows.get(k));
          keptPicked.add(picked.get(k));
        }
      }
      rows = keptRows;
      picked = keptPicked;
      maxIndent = picked.stream().mapToInt(p -> p[1]).max().orElse(0);
    }
    StringBuilder out = new StringBuilder(header);
    int shown = 0;
    for (String r : rows) {
      if (out.length() - header.length() + r.length() > budget) break;
      out.append(r);
      shown++;
    }
    if (shown < rows.size()) out.append("… ").append(rows.size() - shown).append(" more declarations\n");
    return out.toString();
  }

  private static boolean matches(String text) {
    if (text.isBlank()) return false;
    return DECLARATION.matcher(text).find() || BINDING.matcher(text).find() || METHOD.matcher(text).find()
        || HEADING.matcher(text).find() || CSS_RULE.matcher(text).matches() || JSON_KEY.matcher(text).find();
  }

  private static int indent(String text) {
    int n = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == ' ') n++;
      else if (c == '\t') n += 4;
      else break;
    }
    return n;
  }

  private static String row(int line, String text) {
    String t = text.stripTrailing();
    if (t.length() > LINE_CHARS) t = t.substring(0, LINE_CHARS) + " …";
    return String.format(Locale.ROOT, "%6d\t%s%n", line, t).replace("\r\n", "\n");
  }

  private static String importRow(int from, int to, int count) {
    return String.format(Locale.ROOT, "%6d\t[%d import%s, lines %d-%d]%n", from, count, count == 1 ? "" : "s", from, to)
        .replace("\r\n", "\n");
  }

  private static String firstLines(List<String> lines, int first, int budget) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    for (; i < lines.size(); i++) {
      String r = row(first + i, lines.get(i));
      if (out.length() + r.length() > budget) break;
      out.append(r);
    }
    if (i < lines.size()) out.append("… ").append(lines.size() - i).append(" more lines\n");
    return out.toString();
  }

  private static int length(List<String> rows) {
    int n = 0;
    for (String r : rows) n += r.length();
    return n;
  }
}
