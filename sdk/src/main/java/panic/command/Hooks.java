package panic.command;

import context.Context;
import context.EmitException;
import context.HookKey;
import java.util.Optional;

/** Helpers over z hooks. */
public final class Hooks {

  private Hooks() {
  }

  /**
   * {@code ctx.bail(key, event)}, except that the handler's own RuntimeException is thrown as is instead
   * of wrapped in an EmitException, so callers can catch what the handler threw.
   */
  public static <E, R> Optional<R> bail(Context ctx, HookKey<E, R> key, E event) {
    try {
      return ctx.bail(key, event);
    } catch (EmitException e) {
      if (e.failures().size() == 1 && e.failures().get(0).error() instanceof RuntimeException cause) throw cause;
      throw e;
    }
  }
}
