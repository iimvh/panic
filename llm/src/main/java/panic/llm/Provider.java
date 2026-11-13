package panic.llm;

import core.Signal;
import java.util.List;

/**
 * A connected LLM backend, published as the service {@code Providers.key(id())} by its plugin. It exists
 * only while it has a credential: no key, no service.
 */
public interface Provider {

  String id();

  ChatModel chat();

  /** The models it can serve, sorted by id. Throws LlmException when the provider cannot be reached. */
  List<ModelInfo> models(Signal signal);

  /** Checks the credential against the provider itself. Never throws, except when cancelled. */
  KeyCheck checkKey(Signal signal);
}
