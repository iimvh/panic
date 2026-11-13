package panic.util;

import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.List;

/**
 * A Signal that can be cancelled by hand, or that follows other signals ({@link #any}). Same contract
 * as core signals: listeners run exactly once, and a listener added after cancellation runs at once.
 */
public final class CancelToken implements Signal {

  private final List<Runnable> listeners = new ArrayList<>();
  private boolean cancelled;

  /** A signal that is cancelled as soon as any of {@code signals} is. */
  public static CancelToken any(Signal... signals) {
    CancelToken token = new CancelToken();
    for (Signal s : signals) {
      if (s != null) s.onCancel(token::cancel);
    }
    return token;
  }

  public void cancel() {
    List<Runnable> run;
    synchronized (this) {
      if (cancelled) return;
      cancelled = true;
      run = List.copyOf(listeners);
      listeners.clear();
    }
    RuntimeException first = null;
    for (Runnable r : run) {
      try {
        r.run();
      } catch (RuntimeException e) {
        if (first == null) first = e; else first.addSuppressed(e);
      }
    }
    if (first != null) throw first;
  }

  @Override
  public synchronized boolean isCancelled() {
    return cancelled;
  }

  @Override
  public Disposable onCancel(Runnable listener) {
    synchronized (this) {
      if (!cancelled) {
        listeners.add(listener);
        return () -> {
          synchronized (this) {
            listeners.remove(listener);
          }
        };
      }
    }
    listener.run();
    return () -> { };
  }

  @Override
  public void throwIfCancelled() {
    if (isCancelled()) throw new CancelledException("cancelled");
  }
}
