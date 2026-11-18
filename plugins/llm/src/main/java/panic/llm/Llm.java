package panic.llm;

import context.HookKey;
import context.ServiceKey;

/**
 * The model the app currently chats with: a provider plus a selected model and default options. Provided
 * as {@link #KEY} by {@link LlmPlugin} while its provider is connected.
 */
public interface Llm extends ChatModel {

  ServiceKey<Llm> KEY = ServiceKey.of("llm", Llm.class);

  /** Commands of the {@code settings} plugin that switch the provider or the model of {@code llm} (see its README). */
  String PROVIDER_COMMAND = "provider";
  String MODEL_COMMAND = "model";
  String EFFORT_COMMAND = "effort";

  /**
   * Answers with the current Llm, for callers that only want to look at it (list models, check the key)
   * without having their lifetime tied to it. Use {@link #KEY} with requires() to actually depend on it.
   */
  HookKey<Query, Llm> CURRENT = HookKey.of("llm.current", Query.class, Llm.class);

  /** Answers with the selected provider and model, connected or not. */
  HookKey<Query, LlmConfig> CONFIG = HookKey.of("llm.config", Query.class, LlmConfig.class);

  enum Query { CURRENT }

  Provider provider();

  String model();
}
