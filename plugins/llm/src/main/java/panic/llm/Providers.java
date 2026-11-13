package panic.llm;

import context.HookKey;
import context.ServiceKey;

/** Service keys of providers: one per provider id, so each backend is provided and retracted on its own. */
public final class Providers {

  /** One installed provider plugin, connected (has a key) or not. */
  public record Status(String id, boolean connected) {
  }

  public enum Query { ALL }

  /** Every installed provider plugin answers with its status: {@code ctx.collect(STATUS, Query.ALL)}. */
  public static final HookKey<Query, Status> STATUS = HookKey.of("llm.providers", Query.class, Status.class);

  private Providers() {
  }

  public static ServiceKey<Provider> key(String id) {
    return ServiceKey.of("llm.provider." + id, Provider.class);
  }
}
