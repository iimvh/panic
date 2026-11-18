package panic.llm;

import context.ConfigPlugin;
import context.Context;
import context.Plugin;
import core.Disposable;
import core.Signal;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import panic.command.Commands;
import panic.command.Out;

/**
 * Selects the active model. It installs one child plugin that requires the configured provider and, while
 * that provider exists, provides {@link Llm#KEY}. So:
 *  - changing the config restarts only this branch (and what depends on Llm);
 *  - a provider losing its key retracts Llm; getting it back brings Llm back, with no code here.
 *
 * Commands: {@code status} always; {@code models} and {@code check} only while the provider is connected.
 */
public final class LlmPlugin implements ConfigPlugin<LlmConfig> {

  private static final int MODELS_SHOWN = 60;

  @Override
  public Disposable apply(Context ctx, LlmConfig cfg) {
    ctx.hook(Llm.CONFIG, q -> cfg);
    Commands.command(ctx, Commands.STATUS, "show the provider and model in use", call -> {
      String state = ctx.isProvided(Llm.KEY) ? "connected" : "not connected";
      call.out().println("provider: " + orNone(cfg.provider()) + "   model: " + orNone(cfg.model())
          + "   (" + state + ")");
    });
    if (cfg.provider() == null || cfg.provider().isBlank()) return null;
    String providerId = cfg.provider().strip();
    ctx.plugin("active:" + providerId, Plugin.require(Providers.key(providerId)).then(active -> {
      Provider provider = active.get(Providers.key(providerId));
      Llm llm = new Active(provider, cfg);
      active.provide(Llm.KEY, llm);
      active.hook(Llm.CURRENT, q -> llm);
      Commands.command(active, "models [filter]",
          "list the models of " + providerId + " (filter: 'free', or part of an id)",
          call -> models(provider, call.argOr("filter", ""), call.signal(), call.out()));
      Commands.command(active, "check", "check the " + providerId + " API key", call -> {
        KeyCheck check = provider.checkKey(call.signal());
        call.out().println((check.ok() ? "ok: " : "failed: ") + check.detail());
      });
      return null;
    }));
    return null;
  }

  private static void models(Provider provider, String filter, Signal signal, Out out) {
    boolean freeOnly = filter.equalsIgnoreCase("free");
    String needle = freeOnly ? "" : filter.toLowerCase(Locale.ROOT);
    List<ModelInfo> models = provider.models(signal).stream()
        .filter(m -> !freeOnly || Boolean.TRUE.equals(m.free()))
        .filter(m -> needle.isEmpty() || m.id().toLowerCase(Locale.ROOT).contains(needle))
        .toList();
    for (ModelInfo m : models.subList(0, Math.min(models.size(), MODELS_SHOWN))) {
      String ctxLen = m.contextLength() > 0 ? "  " + (m.contextLength() / 1000) + "k" : "";
      String free = Boolean.TRUE.equals(m.free()) ? "  free" : "";
      out.println("  " + m.id() + ctxLen + free);
    }
    if (models.size() > MODELS_SHOWN) out.println("  ... " + (models.size() - MODELS_SHOWN) + " more, add a filter");
    if (models.isEmpty()) out.println("  (none)");
  }

  private static String orNone(String s) {
    return s == null || s.isBlank() ? "<none>" : s;
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

    @Override
    public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta,
        Consumer<ToolCallDelta> onToolDelta) {
      return provider.chat().stream(defaults(request), signal, onDelta, onToolDelta);
    }

    @Override
    public Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta,
        Consumer<ToolCallDelta> onToolDelta, Consumer<String> onReasoning) {
      return provider.chat().stream(defaults(request), signal, onDelta, onToolDelta, onReasoning);
    }

    private ChatRequest defaults(ChatRequest request) {
      return request.withDefaults(cfg.model(), cfg.temperature(), cfg.maxTokens(), cfg.reasoningEffort());
    }

    @Override
    public String toString() {
      return provider.id() + "/" + cfg.model();
    }
  }
}
