package panic.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import panic.llm.Message;
import panic.llm.Role;

/** The message history of one conversation (no system prompt: that belongs to the chat config). Thread-safe. */
public final class Conversation {

  private final String id;
  private final List<Message> messages = new ArrayList<>();

  public Conversation(String id) {
    this.id = Objects.requireNonNull(id, "id");
  }

  public String id() {
    return id;
  }

  /** Appends a finished exchange in one step, so a concurrent reader never sees half of it. */
  public synchronized void append(Message... exchange) {
    messages.addAll(List.of(exchange));
  }

  /**
   * The last {@code max} messages, always starting with a user message: a reply without its question confuses
   * models, and a tool result without the call it answers is rejected by providers. {@code max <= 0} means
   * everything.
   */
  public synchronized List<Message> recent(int max) {
    int from = max <= 0 ? 0 : Math.max(0, messages.size() - max);
    while (from < messages.size() && messages.get(from).role() != Role.USER) from++;
    return List.copyOf(messages.subList(from, messages.size()));
  }

  public synchronized int size() {
    return messages.size();
  }

  public synchronized void clear() {
    messages.clear();
  }
}
