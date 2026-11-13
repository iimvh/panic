package panic.command;

import java.util.ArrayList;
import java.util.List;

/** Splits an argument line on whitespace; "double" or 'single' quotes group words. */
final class Tokens {

  /** A token and where it starts in the line (for rest parameters, which take the raw remainder). */
  record Token(String value, int start) {
  }

  private Tokens() {
  }

  static List<Token> split(String line) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    int n = line.length();
    while (i < n) {
      while (i < n && Character.isWhitespace(line.charAt(i))) i++;
      if (i >= n) break;
      int start = i;
      StringBuilder value = new StringBuilder();
      char quote = 0;
      while (i < n) {
        char c = line.charAt(i);
        if (quote != 0) {
          if (c == quote) quote = 0; else value.append(c);
        } else if (c == '"' || c == '\'') {
          quote = c;
        } else if (Character.isWhitespace(c)) {
          break;
        } else {
          value.append(c);
        }
        i++;
      }
      out.add(new Token(value.toString(), start));
    }
    return out;
  }

  /** Quotes an argument so that split() gives it back as one token. */
  static String quote(String arg) {
    if (!arg.isEmpty() && arg.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'')) {
      return arg;
    }
    return arg.contains("\"") ? "'" + arg + "'" : "\"" + arg + "\"";
  }
}
