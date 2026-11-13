package panic.trace.store;

import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import panic.command.Commands;
import panic.trace.Span;
import panic.trace.Trace;

/**
 * Keeps the traces emitted on {@link Trace#SPAN}: spans are held until their root ends, then the whole trace is
 * recorded in memory and appended to the conversation's file under {@link TracingConfig#dir} by a writer task.
 * The listener never touches a file, so the traced turn never waits for the disk.
 *
 * Answers {@link Trace#GET} and {@link Trace#LIST}. At start the writer first cleans the directory: traces past
 * {@code keepDays} or beyond {@code maxTraces} per conversation are dropped, damaged lines repaired. Requires
 * nothing; when disposed it stops listening, stops the writer, then writes what is still queued (even if the
 * writer never got to start) before returning. One process per directory, as for conversations.
 *
 * Commands: {@code traces [count]}, {@code trace <id>}, {@code stats}, over the session's conversation.
 */
public final class TracingPlugin implements ConfigPlugin<TracingConfig> {

  /** Traces whose root has not ended yet, at most this many; the oldest is dropped beyond. */
  static final int MAX_OPEN_TRACES = 256;

  private final Consumer<String> warn;

  public TracingPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  @Override
  public Disposable apply(Context ctx, TracingConfig cfg) {
    Path dir = directory(Path.of(cfg.dir()).toAbsolutePath().normalize());
    TraceStore store = new TraceStore(dir, cfg.maxTraces(), cfg.keepDays() * 86_400L * 1_000_000L, warn);
    BlockingQueue<Recorded> pending = new LinkedBlockingQueue<>();

    // Disposal runs in reverse: the listener goes first, then the writer, then this flush of what is left.
    if (dir != null) {
      ctx.effect("flush", s -> () -> {
        for (Recorded t; (t = pending.poll()) != null; ) write(store, t);
      });
      ctx.spawn("writer", signal -> {
        clean(store);
        while (true) {
          Recorded t;
          try {
            t = pending.take();
          } catch (InterruptedException e) {
            return null; // stopping: the flush writes the rest
          }
          write(store, t);
        }
      });
    }

    Map<String, List<Span>> open = new LinkedHashMap<>(16, 0.75f, false) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, List<Span>> eldest) {
        return size() > MAX_OPEN_TRACES;
      }
    };
    ctx.on(Trace.SPAN, span -> {
      Recorded done;
      synchronized (open) {
        open.computeIfAbsent(span.traceId(), k -> new ArrayList<>()).add(span);
        if (!span.root()) return;
        done = new Recorded(span, open.remove(span.traceId()));
      }
      store.add(done);
      if (dir != null) pending.add(done);
    });

    ctx.hook(Trace.GET, q -> store.find(q.conversationId(), q.traceId())
        .map(t -> new Trace.Spans(t.spans())).orElse(null));
    ctx.hook(Trace.LIST, q -> {
      List<Recorded> traces = store.traces(q.conversationId());
      int from = q.limit() <= 0 ? 0 : Math.max(0, traces.size() - q.limit());
      List<Span> roots = new ArrayList<>();
      for (int i = traces.size() - 1; i >= from; i--) roots.add(traces.get(i).root());
      return new Trace.Roots(roots);
    });

    Commands.command(ctx, "traces [count]", "list this session's latest traces (default 10)", call -> {
      int count = count(call.arg("count"));
      if (count <= 0) call.out().println("count must be a positive number, not " + call.arg("count"));
      else Report.list(call.out(), store.traces(call.session().id()), count);
    });
    Commands.command(ctx, "trace <id>", "show one trace of this session as a tree (an id prefix is enough)",
        call -> Report.tree(call.out(), store.matches(call.session().id(), call.arg("id")), call.arg("id")));
    Commands.command(ctx, "stats", "latency, tokens and tool use over this session's traces",
        call -> Report.stats(call.out(), store.traces(call.session().id())));
    return null;
  }

  /** The {@code traces} count: 10 when not given, 0 when not a positive number. */
  private static int count(String arg) {
    if (arg == null) return 10;
    try {
      return Math.max(0, Integer.parseInt(arg.strip()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private void write(TraceStore store, Recorded trace) {
    store.io.lock();
    try {
      TraceFile.append(store.dir().resolve(TraceFile.fileName(trace.conversationId())), trace, warn);
    } finally {
      store.io.unlock();
    }
  }

  /** Applies the retention to every file of the directory, and repairs damaged ones. */
  private void clean(TraceStore store) {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> s = Files.newDirectoryStream(store.dir(), "*.jsonl")) {
      s.forEach(files::add);
    } catch (IOException e) {
      warn.accept("[tracing] cannot list " + store.dir() + ": " + e);
      return;
    }
    long now = TraceStore.nowMicros();
    for (Path file : files) {
      store.io.lock();
      try {
        TraceFile.Read read = TraceFile.read(file, warn);
        List<Recorded> kept = store.retained(read.traces(), now);
        if (read.dirty() || kept.size() != read.traces().size()) TraceFile.rewrite(file, kept, warn);
      } finally {
        store.io.unlock();
      }
    }
  }

  /** {@code dir}, created if needed; null (memory only, with a warning) when it cannot be. */
  private Path directory(Path dir) {
    try {
      return Files.createDirectories(dir);
    } catch (IOException e) {
      warn.accept("[tracing] cannot create " + dir + ": " + e + "; traces are kept in memory only");
      return null;
    }
  }
}
