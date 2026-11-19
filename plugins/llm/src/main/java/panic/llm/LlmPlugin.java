package panic.llm;

import context.ConfigPlugin;
import context.Context;
import context.Plugin;
import core.Disposable;
import core.Signal;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
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
      ctx.bail(Llm.CURRENT, Llm.Query.CURRENT).ifPresent(llm -> call.out().println(limits(cfg, llm)));
    });
    if (cfg.provider() == null || cfg.provider().isBlank()) return null;
    String providerId = cfg.provider().strip();
    ctx.plugin("active:" + providerId, Plugin.require(Providers.key(providerId)).then(active -> {
      Provider provider = active.get(Providers.key(providerId));
      AtomicReference<ModelInfo> info = new AtomicReference<>();
      if (cfg.model() != null) {
        active.spawn("model-info", signal -> {
          info.set(lookup(provider, cfg.model(), signal));
          return null;
        });
      }
      Llm llm = new Active(provider, cfg, info);
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

  /** What the provider lists for {@code model}; null when the list fails or does not have it. */
  static ModelInfo lookup(Provider provider, String model, Signal signal) {
    try {
      for (ModelInfo m : provider.models(signal)) {
        if (m.id().equals(model)) return m;
      }
    } catch (RuntimeException e) {
      // unknown: callers fall back to their own defaults
    }
    return null;
  }

  /** The limits in use and where they come from, for {@code status}. */
  static String limits(LlmConfig cfg, Llm llm) {
    ModelInfo info = llm.info();
    long context = cfg.contextWindow() != null ? cfg.contextWindow() : info == null ? 0 : info.contextLength();
    String source = cfg.contextWindow() != null ? "config" : "provider";
    StringBuilder out = new StringBuilder("context: ")
        .append(context > 0 ? (context / 1000) + "k (" + source + ")" : "unknown");
    if (cfg.maxTokens() != null) out.append("   reply: ").append(cfg.maxTokens()).append(" (config)");
    else if (info != null && info.maxOutput() > 0) out.append("   reply: up to ").append(info.maxOutput() / 1000)
        .append("k (provider)");
    out.append("   effort: ").append(cfg.reasoningEffort() == null ? "off" : cfg.reasoningEffort());
    if (cfg.reasoningEffort() != null && info != null && Boolean.FALSE.equals(info.reasoning())) {
      out.append(" (the model takes none: not sent)");
    }
    return out.toString();
  }

  private static String orNone(String s) {
    return s == null || s.isBlank() ? "<none>" : s;
  }

  /** {@code looked}: what the provider lists for the model, null while unknown. */
  private record Active(Provider provider, LlmConfig cfg, AtomicReference<ModelInfo> looked) implements Llm {

    @Override
    public String model() {
      return cfg.model();
    }

    @Override
    public ModelInfo info() {
      return looked.get();
    }

    @Override
    public int inputWindow() {
      ModelInfo m = looked.get();
      long length = cfg.contextWindow() != null ? cfg.contextWindow() : m == null ? 0 : m.contextLength();
      if (length <= 0) return 0;
      long reply = cfg.maxTokens() != null ? Math.max(0, cfg.maxTokens())
          : m != null && m.maxOutput() > 0 ? Math.min(m.maxOutput(), REPLY_ROOM) : DEFAULT_REPLY_ROOM;
      // a reply room as large as the window (a tiny or misdescribed model) leaves half for the request
      if (reply >= length) reply = length / 2;
      return (int) Math.min(Integer.MAX_VALUE, length - reply);
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

    /**
     * The config's options for what the request leaves out. The configured effort is not sent to a model the
     * provider lists without a reasoning parameter (it would refuse the request or ignore it); a request that asks
     * for an effort itself still sends it, and so does every request while nobody knows.
     */
    private ChatRequest defaults(ChatRequest request) {
      ModelInfo m = looked.get();
      String effort = m != null && Boolean.FALSE.equals(m.reasoning()) ? null : cfg.reasoningEffort();
      return request.withDefaults(cfg.model(), cfg.temperature(), cfg.maxTokens(), effort);
    }

    @Override
    public String toString() {
      return provider.id() + "/" + cfg.model();
    }
  }
}
