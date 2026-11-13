package panic.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import panic.llm.Message;
import panic.llm.Role;

/**
 * The message history of one conversation (no system prompt: that belongs to the chat config), and the
 * summary of the older messages that compaction replaced. Thread-safe.
 *
 * Every change is passed to its {@link Journal} while the lock is held, so the stored copy sees changes in the
 * same order as readers do.
 */
public final class Conversation {

  /** The summary (null when there is none) and the messages, read together. */
  public record Snapshot(String summary, List<Message> messages) {
  }

  /**
   * Where a conversation keeps a durable copy (see {@link ConversationsPlugin}). Called under the
   * conversation's lock, after the change. It must not throw: a storage failure is reported, not fatal.
   */
  interface Journal {
    Journal NONE = new Journal() {
      @Override
      public void append(List<Message> exchange) {
      }

      @Override
      public void rewrite(String summary, List<Message> messages) {
      }

      @Override
      public void delete() {
      }
    };

    /** Messages added at the end. */
    void append(List<Message> exchange);

    /** The whole history replaced (after a compaction). */
    void rewrite(String summary, List<Message> messages);

    /** The history cleared. */
    void delete();
  }

  private final String id;
  private final List<Message> messages = new ArrayList<>();
  private final Journal journal;
  private String summary;

  public Conversation(String id) {
    this(id, Journal.NONE, null, List.of());
  }

  /** A conversation restored from storage, which records its later changes in {@code journal}. */
  Conversation(String id, Journal journal, String summary, List<Message> loaded) {
    this.id = Objects.requireNonNull(id, "id");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.summary = summary;
    messages.addAll(loaded);
  }

  public String id() {
    return id;
  }

  /** Appends a finished exchange in one step, so a concurrent reader never sees half of it. */
  public synchronized void append(Message... exchange) {
    List<Message> added = List.of(exchange);
    if (added.isEmpty()) return;
    messages.addAll(added);
    journal.append(added);
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
    journal.rewrite(summary, List.copyOf(messages));
    return true;
  }

  public synchronized int size() {
    return messages.size();
  }

  public synchronized void clear() {
    messages.clear();
    summary = null;
    journal.delete();
  }
}
