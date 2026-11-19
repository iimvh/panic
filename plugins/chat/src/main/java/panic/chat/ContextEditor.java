package panic.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import panic.llm.Message;
import panic.llm.Role;
import panic.tools.Resource;
import panic.tools.ToolResult;

/**
 * Which tool results one turn's requests show in full, the context as a cache of what the model has learned. Old
 * results are what fills a long turn, so some must give way. Which ones barely matters: when the working set (the
 * files the model needs at once) fits, nothing has to go, and when it does not fit, any order makes the model re-read
 * in a cycle (the 2026-11-18 trace: 795 reads of 7 files, no code). What helps is making the context smaller, so the
 * working set fits: that is all this class does beyond clearing by age.
 *
 * <ul>
 *   <li><b>Hysteresis</b>: results are cleared only when the request <em>as it will be sent</em> is estimated over
 *       {@code trigger}, and then oldest-first only until it is under {@code target}; a result once cleared stays
 *       cleared, so the requests keep a stable prefix (the provider's prompt cache) and clearing happens in rare
 *       batches, not on every call.</li>
 *   <li><b>Resources</b> ({@link Options#resources}): a read of a part already shown, unchanged, gets a one-line
 *       pointer instead of the same text again ({@link #unchanged}), and a cleared view of a file that changed since
 *       says so.</li>
 *   <li><b>Compact forms</b> ({@link Options#compact}): a cleared result whose tool gave a short form (a file's
 *       outline) is replaced by that form rather than by a bare placeholder, so the model keeps a map of the file
 *       and reads back only the part it needs.</li>
 * </ul>
 *
 * When even that is not enough, the turn's older steps are summarised ({@link TurnCompaction}); {@link #forget}
 * then drops what they showed. Generic: it knows nothing about files, only what tools declare. One per turn, used
 * from the turn's loop only.
 */
final class ContextEditor {

  /** The mechanisms beyond hysteresis, each on by default; off only to measure what it brings (eval/README.md). */
  record Options(boolean resources, boolean compact) {
    static final Options ALL = new Options(true, true);
    static final Options NONE = new Options(false, false);
  }

  /** Estimates tokens; the turn's {@link Tokens}, or a fixed ratio in tests and benches. */
  interface Estimator {
    int estimate(Message m);
  }

  /** What one {@link #apply} did, for the {@code llm} span. */
  record Applied(int cleared, int compacted, int estimate) {
  }

  private record Entry(String callId, String tool, Resource resource, String compact, int step, int contentHash,
      int length) {
  }

  /** What a cleared result says instead, unless its tool gave a compact form. */
  static final String CLEARED = "[%s result cleared to save context (%d chars); it was shown before: call it again "
      + "only if you need the exact text]";
  static final String STALE = "[%s result cleared: %s changed after this read (step %d); read it again for the "
      + "current text]";
  static final String COMPACTED = "[%s result cleared to save context (%d chars); its outline is kept]\n%s";
  static final String UNCHANGED = "[unchanged: %s lines %d-%d are exactly as in your %s result of step %d, which "
      + "is still above; use that]";

  private final Options options;
  private final Map<String, Entry> entries = new HashMap<>();
  /** Result ids in the order they were added. */
  private final List<String> order = new ArrayList<>();
  /** Cleared results and what each says now: decided once, so every later request is the same there. */
  private final Map<String, String> cleared = new HashMap<>();
  /** Cleared results that show their compact form: the only ones that can shrink again (to a placeholder). */
  private final Set<String> compactedIds = new HashSet<>();
  /** Results no longer in the requests at all: their step was summarised ({@link #forget}). */
  private final Set<String> gone = new HashSet<>();
  /** Keys whose views were cleared or summarised: reading one again is a miss, counted to show thrashing. */
  private final Set<String> ghosts = new HashSet<>();
  private int refetches;
  private int unchangedHits;

  ContextEditor(Options options) {
    this.options = options;
  }

  int refetches() {
    return refetches;
  }

  int unchangedHits() {
    return unchangedHits;
  }

  /**
   * Before a result is added: a pointer to the visible result that already shows the same lines of the same version,
   * or null when the result must be shown. Only with {@link Options#resources}.
   */
  String unchanged(String tool, ToolResult result) {
    if (!options.resources() || result.isError()) return null;
    Resource r = result.resource();
    if (r == null || !r.isView()) return null;
    for (int i = order.size() - 1; i >= 0; i--) {
      Entry e = entries.get(order.get(i));
      if (e.resource() == null || !e.resource().key().equals(r.key())) continue;
      if (!e.resource().isView()) return null; // written since: whatever is above predates it
      if (cleared.containsKey(e.callId()) || gone.contains(e.callId())) continue;
      if (e.resource().covers(r)) {
        unchangedHits++;
        return String.format(Locale.ROOT, UNCHANGED, label(r.key()), r.from(), r.to(), e.tool(), e.step());
      }
    }
    return null;
  }

  /**
   * After a result was added as the content ({@code content}) of {@code callId}'s tool message; true when it reads
   * again a file whose view was cleared (a miss). A pointer from {@link #unchanged} is recorded without its resource.
   */
  boolean record(String callId, String tool, ToolResult result, String content, int step) {
    Resource r = result.isError() ? null : result.resource();
    boolean refetch = false;
    if (r != null && r.isView() && ghosts.contains(r.key())) {
      refetch = true;
      refetches++;
    }
    String compact = options.compact() ? result.compact() : null;
    entries.put(callId, new Entry(callId, tool, r, compact, step, content.hashCode(), content.length()));
    order.add(callId);
    return refetch;
  }

  /**
   * Rewrites {@code messages} (the request about to be sent) in place: every result cleared before stays cleared,
   * then, if the request is still estimated over {@code trigger}, more are cleared, oldest first, until it
   * is under {@code target}. The newest {@code keep} results are never cleared. When clearing results is not enough,
   * the compact forms left by earlier clearings shrink to placeholders too (the older outlines of a file first):
   * compact forms must not pile up past the budget they were meant to save. {@code extra} is the rest of the
   * request (the tools).
   */
  Applied apply(List<Message> messages, Estimator est, int extra, int trigger, int target, int keep) {
    long total = extra;
    for (int i = 0; i < messages.size(); i++) {
      Message m = messages.get(i);
      if (m.role() == Role.TOOL && cleared.containsKey(m.toolCallId())) {
        m = m.withContent(cleared.get(m.toolCallId()));
        messages.set(i, m);
      }
      total += est.estimate(m);
    }
    if (trigger > 0 && total > trigger) {
      List<Integer> candidates = candidates(messages, keep);
      for (int index : candidates) {
        if (total <= target) break;
        Message m = messages.get(index);
        String replacement = replacement(m);
        if (replacement.length() >= m.content().length()) continue;
        Message cut = m.withContent(replacement);
        total += est.estimate(cut) - est.estimate(m);
        messages.set(index, cut);
        cleared.put(m.toolCallId(), replacement);
        Entry e = entries.get(m.toolCallId());
        if (e != null && e.compact() != null && replacement.endsWith(e.compact())) compactedIds.add(e.callId());
        if (e != null && e.resource() != null && e.resource().isView()) ghosts.add(e.resource().key());
      }
      if (total > target) total = shrinkCompacted(messages, est, total, target);
    }
    int compacted = 0;
    int clearedNow = 0;
    for (Message m : messages) {
      if (m.role() != Role.TOOL || !cleared.containsKey(m.toolCallId())) continue;
      clearedNow++;
      if (compactedIds.contains(m.toolCallId())) compacted++;
    }
    return new Applied(clearedNow, compacted, (int) Math.min(Integer.MAX_VALUE, total));
  }

  /** Compact forms back to placeholders, those of a key that has a newer one first, then the oldest, to target. */
  private long shrinkCompacted(List<Message> messages, Estimator est, long total, int target) {
    List<Integer> shown = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).role() == Role.TOOL && compactedIds.contains(messages.get(i).toolCallId())) shown.add(i);
    }
    Set<String> newer = new HashSet<>();
    List<Integer> repeated = new ArrayList<>();
    List<Integer> rest = new ArrayList<>();
    for (int k = shown.size() - 1; k >= 0; k--) {
      Entry e = entries.get(messages.get(shown.get(k)).toolCallId());
      String key = e.resource() == null ? e.callId() : e.resource().key();
      if (newer.add(key)) rest.add(0, shown.get(k));
      else repeated.add(0, shown.get(k));
    }
    List<Integer> order = new ArrayList<>(repeated);
    order.addAll(rest);
    for (int index : order) {
      if (total <= target) break;
      Message m = messages.get(index);
      Entry e = entries.get(m.toolCallId());
      String bare = String.format(Locale.ROOT, CLEARED, e.tool(), e.length());
      if (bare.length() >= m.content().length()) continue;
      Message cut = m.withContent(bare);
      total += est.estimate(cut) - est.estimate(m);
      messages.set(index, cut);
      cleared.put(m.toolCallId(), bare);
      compactedIds.remove(m.toolCallId());
    }
    return total;
  }

  /** Indexes of the tool messages that may be cleared, in the order they should be. */
  private List<Integer> candidates(List<Message> messages, int keep) {
    List<Integer> tools = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      Message m = messages.get(i);
      if (m.role() == Role.TOOL && !cleared.containsKey(m.toolCallId())) tools.add(i);
    }
    int open = Math.max(0, tools.size() - keep);
    return new ArrayList<>(tools.subList(0, open));
  }

  /** False when a later result shows or writes another version of the same key. */
  private boolean current(Entry e) {
    for (int i = order.indexOf(e.callId()) + 1; i < order.size(); i++) {
      Entry later = entries.get(order.get(i));
      if (later.resource() != null && later.resource().key().equals(e.resource().key())
          && !later.resource().version().equals(e.resource().version())) {
        return false;
      }
    }
    return true;
  }

  /**
   * The results of {@code callIds} are no longer sent at all (their steps were summarised): nothing may point to
   * them, and reading their files again counts as a miss.
   */
  void forget(Collection<String> callIds) {
    for (String id : callIds) {
      Entry e = entries.get(id);
      if (e == null || !gone.add(id)) continue;
      compactedIds.remove(id);
      if (e.resource() != null && e.resource().isView()) ghosts.add(e.resource().key());
    }
  }

  /**
   * What the turn's tools did, from what they declared, for a summary that must not lose it: the files written (with
   * their last version) and read, and how often each tool ran. Exact where a model's summary may not be.
   */
  String manifest() {
    Map<String, String> written = new LinkedHashMap<>();
    Set<String> read = new LinkedHashSet<>();
    Map<String, Integer> calls = new LinkedHashMap<>();
    for (String id : order) {
      Entry e = entries.get(id);
      calls.merge(e.tool(), 1, Integer::sum);
      if (e.resource() == null) continue;
      if (e.resource().isView()) read.add(label(e.resource().key()));
      else written.put(label(e.resource().key()), e.resource().version());
    }
    StringBuilder out = new StringBuilder();
    if (!written.isEmpty()) out.append("Files changed (path @ version): ").append(join(written, " @ ")).append('\n');
    if (!read.isEmpty()) out.append("Files read: ").append(String.join(", ", read)).append('\n');
    if (!calls.isEmpty()) out.append("Tool calls: ").append(join(calls, " \u00d7")).append('\n');
    return out.toString().stripTrailing();
  }

  /**
   * The compact forms (outlines) of the current versions of files whose every view was summarised away, newest
   * first, while they fit {@code maxTokens}; empty when there are none. A summary keeps the map and drops the trail:
   * outlines grow with the files the task touches, not with its steps, and without them the model can only read
   * whole files again where it would have read the part it needs.
   */
  String outlines(Estimator est, int maxTokens) {
    StringBuilder out = new StringBuilder();
    Set<String> done = new HashSet<>();
    int used = 0;
    for (int i = order.size() - 1; i >= 0; i--) {
      Entry e = entries.get(order.get(i));
      Resource r = e.resource();
      if (r == null || !gone.contains(e.callId())) continue;
      if (!done.add(r.key()) || e.compact() == null || !r.isView() || !current(e) || shown(r)) continue;
      String part = "### " + label(r.key()) + "\n" + e.compact() + "\n";
      int cost = est.estimate(Message.user(part));
      if (used + cost > maxTokens) break;
      used += cost;
      out.append(part);
    }
    return out.isEmpty() ? "" : "Outlines of files read earlier (as they are now):\n" + out.toString().stripTrailing();
  }

  /** True when a result still sent (in full or as its compact form) shows this version of the key. */
  private boolean shown(Resource r) {
    for (Entry e : entries.values()) {
      if (gone.contains(e.callId()) || e.resource() == null || !e.resource().isView()) continue;
      if (e.resource().key().equals(r.key()) && e.resource().version().equals(r.version())
          && (!cleared.containsKey(e.callId()) || compactedIds.contains(e.callId()))) {
        return true;
      }
    }
    return false;
  }

  private static String join(Map<String, ?> map, String between) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, ?> e : map.entrySet()) {
      if (out.length() > 0) out.append(", ");
      out.append(e.getKey()).append(between).append(e.getValue());
    }
    return out.toString();
  }

  private String replacement(Message m) {
    Entry e = entries.get(m.toolCallId());
    if (e == null) return String.format(Locale.ROOT, CLEARED, "tool", m.content().length());
    if (options.resources() && e.resource() != null && e.resource().isView() && !current(e)) {
      return String.format(Locale.ROOT, STALE, e.tool(), label(e.resource().key()), e.step());
    }
    if (e.compact() != null && !outlined(e)) {
      return String.format(Locale.ROOT, COMPACTED, e.tool(), m.content().length(), e.compact());
    }
    return String.format(Locale.ROOT, CLEARED, e.tool(), m.content().length());
  }

  /** True when the compact form of another result of the same key and version is already shown. */
  private boolean outlined(Entry e) {
    if (e.resource() == null) return false;
    for (String id : compactedIds) {
      Entry other = entries.get(id);
      if (other.resource() != null && other.resource().key().equals(e.resource().key())
          && other.resource().version().equals(e.resource().version())) {
        return true;
      }
    }
    return false;
  }

  /** {@code file:src/App.jsx} as {@code src/App.jsx}: the key without its scheme. */
  private static String label(String key) {
    int colon = key.indexOf(':');
    return colon < 0 ? key : key.substring(colon + 1);
  }
}
