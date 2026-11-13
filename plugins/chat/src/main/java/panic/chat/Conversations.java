package panic.chat;

import context.Plugin;
import context.ServiceKey;
import panic.command.Commands;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Where conversations live. A service of its own, independent of the LLM, so switching provider or model
 * (which restarts the chat plugin) keeps every history. Provided by {@link ConversationsPlugin} (on disk), or
 * by {@link #inMemory()} in tests.
 */
public interface Conversations {

  ServiceKey<Conversations> KEY = ServiceKey.of("conversations", Conversations.class);

  /** The conversation with this id, created empty on first use. */
  Conversation get(String id);

  /** In-memory store; history is gone when the plugin is disposed. Command: {@code reset}. For tests. */
  static Plugin inMemory() {
    return ctx -> {
      ConcurrentMap<String, Conversation> all = new ConcurrentHashMap<>();
      ctx.provide(KEY, id -> all.computeIfAbsent(id, Conversation::new));
      Commands.command(ctx, "reset", "forget this session's conversation", call -> {
        Conversation c = all.remove(call.session().id());
        call.out().println(c == null || c.size() == 0 ? "nothing to forget" : "conversation cleared");
      });
      return null;
    };
  }
}
