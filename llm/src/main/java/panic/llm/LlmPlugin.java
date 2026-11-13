package panic.llm;

import context.ConfigPlugin;
import context.Context;
import context.Plugin;
import core.Disposable;
import core.Signal;
import java.util.function.Consumer;

/**
 * Selects the active model. It installs one child plugin that requires the configured provider and, while
 * that provider exists, provides {@link Llm#KEY}. So:
 *  - changing the config restarts only this branch (and what depends on Llm);
 *  - a provider losing its key retracts Llm; getting it back brings Llm back, with no code here.
 */
public final class LlmPlugin implements ConfigPlugin<LlmConfig> {

  @Override
  public Disposable apply(Context ctx, LlmConfig cfg) {
    if (cfg.provider() == null || cfg.provider().isBlank()) return null;
    String providerId = cfg.provider().strip();
    ctx.plugin("active:" + providerId, Plugin.require(Providers.key(providerId)).then(active -> {
      Llm llm = new Active(active.get(Providers.key(providerId)), cfg);
      active.provide(Llm.KEY, llm);
      active.hook(Llm.CURRENT, q -> llm);
      return null;
    }));
    return null;
  }

  private record Active(Provider provider, LlmConfig cfg) implements Llm {

    @Override
    public String model() {
      return cfg.model();
    }

    @Override
    public Completion complete(ChatRequest request, Signal signal) {
      return provider.chat().complete(defaults(request), signal);
    }

    @Override
    public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta) {
      return provider.chat().stream(defaults(request), signal, onDelta);
    }

    private ChatRequest defaults(ChatRequest request) {
      return request.withDefaults(cfg.model(), cfg.temperature(), cfg.maxTokens());
    }

    @Override
    public String toString() {
      return provider.id() + "/" + cfg.model();
    }
  }
}
