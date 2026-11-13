package panic.trace.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import panic.trace.Span;

/**
 * The traces of every conversation, in memory, at most {@code maxTraces} each. A conversation's file is read the
 * first time someone asks for its traces and merged with what was recorded meanwhile, so recording never reads
 * a file and never waits for one. All file access, here and in the writer, holds {@link #io}, so a reader never
 * sees a half-written trace.
 */
final class TraceStore {

  /** Taken around every read and write of the trace files. */
  final ReentrantLock io = new ReentrantLock();

  private final Path dir;
  private final int maxTraces;
  private final long keepMicros;
  private final Consumer<String> warn;
  private final Map<String, Entry> all = new HashMap<>();

  private static final class Entry {
    final Map<String, Recorded> traces = new LinkedHashMap<>();
    boolean loaded;
  }

  /** {@code dir} null = memory only. {@code keepMicros} 0 = no age limit. */
  TraceStore(Path dir, int maxTraces, long keepMicros, Consumer<String> warn) {
    this.dir = dir;
    this.maxTraces = maxTraces;
    this.keepMicros = keepMicros;
    this.warn = warn;
  }

  Path dir() {
    return dir;
  }

  /** Records a finished trace. Cheap: no file access. */
  synchronized void add(Recorded trace) {
    Entry e = all.computeIfAbsent(trace.conversationId(), k -> new Entry());
    e.traces.put(trace.id(), trace);
    trim(e);
  }

  /** The conversation's traces, oldest first. */
  List<Recorded> traces(String conversationId) {
    load(conversationId);
    synchronized (this) {
      Entry e = all.get(conversationId);
      return e == null ? List.of() : List.copyOf(e.traces.values());
    }
  }

  /** Forgets every trace of {@code conversationId}, in memory and on disk (a failure is a warning). */
  void delete(String conversationId) {
    synchronized (this) {
      all.remove(conversationId);
    }
    if (dir == null) return;
    Path file = dir.resolve(TraceFile.fileName(conversationId));
    io.lock();
    try {
      java.nio.file.Files.deleteIfExists(file);
    } catch (java.io.IOException e) {
      warn.accept("[tracing] cannot delete " + file + ": " + e);
    } finally {
      io.unlock();
    }
  }

  /** The trace whose id is {@code id} or, failing that, starts with it when only one does. */
  Optional<Recorded> find(String conversationId, String id) {
    List<Recorded> matches = matches(conversationId, id);
    return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
  }

  /** The exact trace, or every trace whose id starts with {@code id}. */
  List<Recorded> matches(String conversationId, String id) {
    if (id == null || id.isBlank()) return List.of();
    List<Recorded> traces = traces(conversationId);
    for (Recorded t : traces) {
      if (t.id().equals(id)) return List.of(t);
    }
    return traces.stream().filter(t -> t.id().startsWith(id)).toList();
  }

  /** Whether a span this old (start, epoch micros) is past {@code keepDays}. */
  boolean expired(Span root, long nowMicros) {
    return keepMicros > 0 && root.startMicros() < nowMicros - keepMicros;
  }

  private void load(String conversationId) {
    synchronized (this) {
      Entry e = all.get(conversationId);
      if (e != null && e.loaded) return;
    }
    List<Recorded> read = List.of();
    if (dir != null) {
      io.lock();
      try {
        read = TraceFile.read(dir.resolve(TraceFile.fileName(conversationId)), warn).traces();
      } finally {
        io.unlock();
      }
    }
    long now = nowMicros();
    synchronized (this) {
      Entry e = all.computeIfAbsent(conversationId, k -> new Entry());
      if (e.loaded) return;
      // what the file holds comes first; traces recorded meanwhile (some maybe not written yet) after
      List<Recorded> merged = new ArrayList<>();
      for (Recorded t : read) {
        if (!expired(t.root(), now) && !e.traces.containsKey(t.id())) merged.add(t);
      }
      merged.addAll(e.traces.values());
      merged.sort(Comparator.comparingLong(t -> t.root().startMicros()));
      e.traces.clear();
      for (Recorded t : merged) e.traces.put(t.id(), t);
      e.loaded = true;
      trim(e);
    }
  }

  /** {@code traces} cut to the newest {@code maxTraces} and to the age limit; the order is kept. */
  List<Recorded> retained(List<Recorded> traces, long nowMicros) {
    List<Recorded> kept = traces.stream().filter(t -> !expired(t.root(), nowMicros)).toList();
    return kept.size() <= maxTraces ? kept : kept.subList(kept.size() - maxTraces, kept.size());
  }

  private void trim(Entry e) {
    var it = e.traces.values().iterator();
    for (int extra = e.traces.size() - maxTraces; extra > 0 && it.hasNext(); extra--) {
      it.next();
      it.remove();
    }
  }

  static long nowMicros() {
    java.time.Instant now = java.time.Instant.now();
    return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
  }
}
