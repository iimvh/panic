package panic.tools;

import java.util.Locale;

/** What the policy says about a tool call: run it, ask the user first, or refuse it. */
public enum Permission {
  ALLOW, ASK, DENY;

  /** The name used in config: {@code allow}, {@code ask}, {@code deny}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The permission named {@code name} (any case). */
  public static Permission of(String name) {
    return valueOf(name.strip().toUpperCase(Locale.ROOT));
  }
}
