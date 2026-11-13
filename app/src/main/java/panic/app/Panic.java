package panic.app;

import context.ConfigHandle;
import context.Context;
import context.ErrorHandler;
import context.Root;
import java.util.LinkedHashMap;
import java.util.Map;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversations;
import panic.llm.LlmConfig;
import panic.llm.LlmPlugin;
import panic.llm.Mistral;
import panic.llm.OpenRouter;
import panic.llm.ProviderConfig;

/**
 * The plugin tree of the app, and the handles that change it at runtime:
 *
 * <pre>
 * panic
 *  ├─ openrouter     (config)  provides llm.provider.openrouter while it has a key
 *  ├─ mistral        (config)  provides llm.provider.mistral while it has a key
 *  ├─ llm            (config)  └─ active:&lt;provider&gt;  requires that provider, provides llm
 *  ├─ conversations            provides conversations
 *  └─ chat           (config)  requires llm + conversations, answers chat.ask
 * </pre>
 *
 * Front-ends (REPL, config watcher) are installed on {@link #root()} by the caller.
 */
public final class Panic {

  private final Root root;
  private final Map<String, ConfigHandle<ProviderConfig>> providers = new LinkedHashMap<>();
  private final ConfigHandle<LlmConfig> llm;
  private final ConfigHandle<ChatConfig> chat;
  private PanicConfig applied;

  private Panic(Root root, PanicConfig cfg) {
    this.root = root;
    this.applied = cfg;
    providers.put(OpenRouter.ID, root.plugin(OpenRouter.ID, OpenRouter.plugin(), cfg.provider(OpenRouter.ID)));
    providers.put(Mistral.ID, root.plugin(Mistral.ID, Mistral.plugin(), cfg.provider(Mistral.ID)));
    llm = root.plugin("llm", new LlmPlugin(), cfg.llm());
    root.plugin("conversations", Conversations.inMemory());
    chat = root.plugin("chat", new ChatPlugin(), cfg.chat());
  }

  public static Panic start(PanicConfig cfg, ErrorHandler errors) {
    Root root = Context.root("panic", errors);
    try {
      return new Panic(root, cfg);
    } catch (RuntimeException | Error e) {
      root.dispose();
      throw e;
    }
  }

  public Root root() {
    return root;
  }

  public java.util.Set<String> providerIds() {
    return providers.keySet();
  }

  public LlmConfig llm() {
    return llm.config();
  }

  public void llm(LlmConfig next) {
    llm.update(next);
  }

  /**
   * Applies a freshly loaded config file. Only sections that changed in the FILE since the last apply are
   * pushed, so a runtime change (e.g. /model in the REPL) survives an unrelated edit of the file.
   */
  public synchronized void apply(PanicConfig next) {
    PanicConfig previous = applied;
    applied = next;
    for (Map.Entry<String, ConfigHandle<ProviderConfig>> e : providers.entrySet()) {
      ProviderConfig cfg = next.provider(e.getKey());
      if (!cfg.equals(previous.provider(e.getKey()))) e.getValue().update(cfg);
    }
    if (!next.llm().equals(previous.llm())) llm.update(next.llm());
    if (!next.chat().equals(previous.chat())) chat.update(next.chat());
  }

  public void dispose() {
    root.dispose();
  }
}
