package panic.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import panic.llm.Message;
import panic.llm.Role;

/**
 * The message history of one conversation (no system prompt: that belongs to the chat config), and the
 * summary of the older messages that compaction replaced. Thread-safe.
 */
public final class Conversation {

  /** The summary (null when there is none) and the messages, read together. */
  public record Snapshot(String summary, List<Message> messages) {
  }

  private final String id;
  private final List<Message> messages = new ArrayList<>();
  private String summary;

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

  public synchronized Snapshot snapshot() {
    return new Snapshot(summary, List.copyOf(messages));
  }

  /** The summary of the messages compaction removed, or null. */
  public synchronized String summary() {
    return summary;
  }

  /**
   * Replaces the first messages, which must still be exactly {@code prefix} (the same instances), by
   * {@code summary}. Returns false and changes nothing when the history moved on in between (cleared, or
   * compacted by another turn).
   */
  public synchronized boolean compact(List<Message> prefix, String summary) {
    Objects.requireNonNull(summary, "summary");
    if (prefix.isEmpty() || prefix.size() > messages.size()) return false;
    for (int i = 0; i < prefix.size(); i++) {
      if (messages.get(i) != prefix.get(i)) return false;
    }
    messages.subList(0, prefix.size()).clear();
    this.summary = summary;
    return true;
  }

  public synchronized int size() {
    return messages.size();
  }

  public synchronized void clear() {
    messages.clear();
    summary = null;
  }
}
