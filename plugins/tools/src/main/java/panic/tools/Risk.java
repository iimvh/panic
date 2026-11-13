package panic.tools;

import java.util.Locale;

/**
 * What a tool can do to the world, declared by every {@link Tool}. The permission policy decides per risk
 * (and per tool) whether a call runs, is refused or needs the user's approval.
 */
public enum Risk {
  /** Only looks: reads files, tells the time. */
  READ,
  /** Changes files or other local state. */
  WRITE,
  /** Runs programs. */
  EXEC,
  /** Talks to other machines. */
  NETWORK;

  /** The name used in config and shown to users: {@code read}, {@code write}, ... */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The risk named {@code name} (any case). */
  public static Risk of(String name) {
    return valueOf(name.strip().toUpperCase(Locale.ROOT));
  }
}
