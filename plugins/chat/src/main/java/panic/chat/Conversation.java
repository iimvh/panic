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

      @Override
      public void parent(String parent, String summary, List<Message> messages) {
      }
    };

    /** Messages added at the end. */
    void append(List<Message> exchange);

    /** The whole history replaced (after a compaction). */
    void rewrite(String summary, List<Message> messages);

    /** The history cleared. */
    void delete();

    /** The conversation now works for {@code parent}; {@code summary} and {@code messages} are its content. */
    void parent(String parent, String summary, List<Message> messages);
  }

  private final String id;
  private final List<Message> messages = new ArrayList<>();
  private final Journal journal;
  private String summary;
  private String parent;
  private long updatedAt;

  public Conversation(String id) {
    this(id, Journal.NONE, null, null, List.of(), System.currentTimeMillis());
  }

  /** A conversation restored from storage, which records its later changes in {@code journal}. */
  Conversation(String id, Journal journal, String summary, String parent, List<Message> loaded, long updatedAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.summary = summary;
    this.parent = parent;
    this.updatedAt = updatedAt;
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
    updatedAt = System.currentTimeMillis();
    journal.append(added);
  }

  /**
   * The conversation this one works for (a sub-agent's conversation names the one that started it), or null for
   * a conversation of its own. Kept with the conversation; listings leave such conversations out.
   */
  public synchronized String parent() {
    return parent;
  }

  /** Records the conversation this one works for (see {@link #parent()}). */
  public synchronized void parent(String parent) {
    if (Objects.equals(this.parent, parent)) return;
    this.parent = parent;
    journal.parent(parent, summary, List.copyOf(messages));
  }

  /** When the history last changed (ms since epoch). */
  public synchronized long updatedAt() {
    return updatedAt;
  }

  /** The first question asked, or null when there is none (it may have been compacted away). */
  public synchronized String firstQuestion() {
    for (Message m : messages) {
      if (m.role() == Role.USER) return m.content();
    }
    return null;
  }

  /** True when there is neither a message nor a summary. */
  public synchronized boolean isEmpty() {
    return messages.isEmpty() && summary == null;
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
    updatedAt = System.currentTimeMillis();
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
