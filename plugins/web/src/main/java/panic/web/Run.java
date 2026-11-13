package panic.web;

import com.fasterxml.jackson.databind.JsonNode;
import core.Disposable;
import core.Signal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import panic.llm.CancelToken;

/**
 * One answer in flight for a session, independent of the browser connections watching it. Every event is
 * kept in a log numbered from 1, so a page that reloads (or loses its connection) can attach again and
 * get what it missed ({@link #attach} with the last id it saw), then the rest live.
 *
 * Connections only watch: one going away detaches it, nothing more. The answer stops when {@link #cancel()}
 * is called (Stop, nobody attached for too long) or when the request running it is cancelled (plugin disposal).
 */
final class Run {

  private record Entry(long seq, String event, JsonNode data) {
  }

  private final Duration detachAfter;
  private final CancelToken cancel = new CancelToken();
  private final CancelToken ended = new CancelToken();
  private final List<Entry> log = new ArrayList<>();
  private final List<SseResponse> watchers = new ArrayList<>();
  /** {@code System.nanoTime()} when the last watcher left; meaningless while someone watches. */
  private long idleSince = System.nanoTime();

  /** {@code detachAfter}: how long the answer may run with nobody watching; zero stops it as soon as the last one leaves. */
  Run(Duration detachAfter) {
    this.detachAfter = detachAfter;
  }

  /** Cancelled to stop the answer. */
  Signal cancelled() {
    return cancel;
  }

  void cancel() {
    cancel.cancel();
  }

  /** Logs the event and sends it to every watcher. */
  synchronized void publish(String event, JsonNode data) {
    Entry e = new Entry(log.size() + 1L, event, data);
    log.add(e);
    // a copy: a failed write detaches its watcher from this very loop
    for (SseResponse w : List.copyOf(watchers)) w.send(e.seq(), e.event(), e.data());
  }

  /**
   * Sends {@code sse} every logged event after {@code after}, then the new ones until the run ends or the
   * connection goes. Returns at once (see {@link #await}).
   */
  void attach(SseResponse sse, long after) {
    synchronized (this) {
      for (Entry e : log) {
        if (e.seq() > after) sse.send(e.seq(), e.event(), e.data());
      }
      if (ended.isCancelled() || sse.gone().isCancelled()) return;
      watchers.add(sse);
    }
    sse.gone().onCancel(() -> detach(sse));
  }

  private void detach(SseResponse sse) {
    boolean alone;
    synchronized (this) {
      alone = watchers.remove(sse) && watchers.isEmpty();
      if (alone) idleSince = System.nanoTime();
    }
    if (alone && detachAfter.isZero()) cancel();
  }

  /** True when nobody has watched for longer than {@code detachAfter}. */
  synchronized boolean abandoned() {
    return watchers.isEmpty() && System.nanoTime() - idleSince >= detachAfter.toNanos();
  }

  /** Called once the last event is published: watchers stop waiting. */
  void end() {
    synchronized (this) {
      watchers.clear();
    }
    ended.cancel();
  }

  /** Blocks until the run ends, {@code sse} goes away or {@code signal} is cancelled. */
  void await(SseResponse sse, Signal signal) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    Disposable a = ended.onCancel(latch::countDown);
    Disposable b = sse.gone().onCancel(latch::countDown);
    Disposable c = signal.onCancel(latch::countDown);
    try {
      latch.await();
    } finally {
      c.dispose();
      b.dispose();
      a.dispose();
    }
  }
}
