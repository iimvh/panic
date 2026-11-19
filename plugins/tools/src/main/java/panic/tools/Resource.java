package panic.tools;

import java.util.Objects;

/**
 * What a tool result shows or changes, so the chat can manage the context by what a result is about instead of by
 * its age alone: a <em>view</em> shows lines {@code from}..{@code to} (1-based, inclusive) of {@code key} as it was
 * at {@code version}; a <em>write</em> ({@code from == 0}) changed {@code key} to {@code version}. Keys name
 * things, not calls ({@code file:src/App.jsx}), so a read, a re-read and an edit of the same file meet; a version
 * is anything that changes when the content does (a hash). Tools that report none are managed by age only.
 */
public record Resource(String key, String version, int from, int to) {

  public Resource {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(version, "version");
    if (from < 0 || to < from) throw new IllegalArgumentException("bad range " + from + ".." + to);
  }

  /** Lines {@code from}..{@code to} of {@code key} at {@code version}. */
  public static Resource view(String key, String version, int from, int to) {
    if (from < 1) throw new IllegalArgumentException("a view starts at line 1 or later");
    return new Resource(key, version, from, to);
  }

  /** {@code key} was changed and is now at {@code version}. */
  public static Resource write(String key, String version) {
    return new Resource(key, version, 0, 0);
  }

  public boolean isView() {
    return from > 0;
  }

  /** True when this view shows every line {@code other} (a view of the same key and version) shows. */
  public boolean covers(Resource other) {
    return isView() && other.isView() && key.equals(other.key) && version.equals(other.version)
        && from <= other.from && to >= other.to;
  }
}
