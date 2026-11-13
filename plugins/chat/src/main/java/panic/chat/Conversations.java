package panic.chat;

import context.EventKey;
import context.HookKey;
import context.Plugin;
import context.ServiceKey;
import java.util.List;
import java.util.Objects;
import panic.llm.Message;

/**
 * Where conversations live. A service of its own, independent of the LLM, so switching provider or model
 * (which restarts the chat plugin) keeps every history. Provided by {@link ConversationsPlugin} (on disk), or
 * by {@link #inMemory()} in tests.
 *
 * A store lists the conversations people had ({@link #list}: not the ones that work for another, like a
 * sub-agent's) and deletes them with what works for them ({@link #delete}). Each deleted conversation is
 * announced on {@link #DELETED}, so plugins keeping data per conversation (plans, traces) can drop it.
 *
 * Front-ends do not require this service: they ask {@link #LIST}, {@link #HISTORY} and {@link #REMOVE}, which
 * the store answers while it runs. With no answer there is no store, and they must cope with that.
 */
public interface Conversations {

  ServiceKey<Conversations> KEY = ServiceKey.of("conversations", Conversations.class);

  /** A conversation as listed: {@code title} is its first question (one line, cut), or "" when there is none. */
  record Info(String id, String title, long updatedAt) {
    public Info {
      Objects.requireNonNull(id, "id");
      if (title == null) title = "";
    }
  }

  /** A conversation was deleted (one event per conversation, its sub-agents' included). */
  record Deleted(String id) {
  }

  EventKey<Deleted> DELETED = EventKey.of("conversations.deleted", Deleted.class);

  /** Asks for the listed conversations (see {@link #list}). */
  record ListQuery() {
  }

  record Listing(List<Info> conversations) {
    public Listing {
      conversations = List.copyOf(conversations);
    }
  }

  /** Asks for the whole stored history of a conversation, oldest first. Unknown ids answer an empty history. */
  record HistoryQuery(String id) {
    public HistoryQuery {
      Objects.requireNonNull(id, "id");
    }
  }

  record History(List<Message> messages) {
    public History {
      messages = List.copyOf(messages);
    }
  }

  /** Asks to delete a conversation (see {@link #delete}). */
  record RemoveQuery(String id) {
    public RemoveQuery {
      Objects.requireNonNull(id, "id");
    }
  }

  record Removal(boolean deleted) {
  }

  HookKey<ListQuery, Listing> LIST = HookKey.of("conversations.list", ListQuery.class, Listing.class);
  HookKey<HistoryQuery, History> HISTORY = HookKey.of("conversations.history", HistoryQuery.class, History.class);
  HookKey<RemoveQuery, Removal> REMOVE = HookKey.of("conversations.remove", RemoveQuery.class, Removal.class);

  /** The conversation with this id, created empty on first use. */
  Conversation get(String id);

  /**
   * The conversations that are not empty and work for no other ({@link Conversation#parent()} is null), most
   * recently changed first. Empty for a store that cannot list.
   */
  default List<Info> list() {
    return List.of();
  }

  /**
   * Deletes conversation {@code id} and every conversation working for it, from memory and storage, and emits
   * {@link #DELETED} for each. False when there was nothing to delete (or the store cannot delete).
   */
  default boolean delete(String id) {
    return false;
  }

  /** In-memory store; history is gone when the plugin is disposed. Commands as {@link ConversationsPlugin}. For tests. */
  static Plugin inMemory() {
    return ctx -> {
      ConversationStore store = new ConversationStore(ctx, null, w -> { });
      ctx.provide(KEY, store);
      store.commands(ctx);
      store.hooks(ctx);
      return null;
    };
  }
}
