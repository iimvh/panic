package panic.chat;

import context.Plugin;
import context.ServiceKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Where conversations live. A service of its own, independent of the LLM, so switching provider or model
 * (which restarts the chat plugin) keeps every history.
 */
public interface Conversations {

  ServiceKey<Conversations> KEY = ServiceKey.of("conversations", Conversations.class);

  /** The conversation with this id, created empty on first use. */
  Conversation get(String id);

  /** In-memory store; history is gone when the plugin is disposed. */
  static Plugin inMemory() {
    return ctx -> {
      ConcurrentMap<String, Conversation> all = new ConcurrentHashMap<>();
      ctx.provide(KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    };
  }
}
