package panic.llm;

import context.ServiceKey;

/** Service keys of providers: one per provider id, so each backend is provided and retracted on its own. */
public final class Providers {

  private Providers() {
  }

  public static ServiceKey<Provider> key(String id) {
    return ServiceKey.of("llm.provider." + id, Provider.class);
  }
}
