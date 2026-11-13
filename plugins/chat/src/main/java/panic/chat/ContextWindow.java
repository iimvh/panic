package panic.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import panic.llm.Message;
import panic.llm.Role;

/**
 * Chooses the part of a history that fits a budget. History is cut by whole turns: a turn is a user message
 * and everything after it up to the next one (assistant tool calls, their results, the answer). So a tool call
 * is never sent without its results, and what is kept always starts with a user message, which providers and
 * models expect.
 */
final class ContextWindow {

  /** {@code kept}: the newest turns that fit, oldest first. {@code dropped}: everything before them. */
  record Fit(List<Message> kept, List<Message> dropped) {
  }

  private ContextWindow() {
  }

  /**
   * Keeps the newest turns while they fit both limits, stopping at the first that does not (history is never
   * sent with a hole in it). {@code maxMessages <= 0} means no count limit; {@code maxTokens} is the token
   * limit, {@code Integer.MAX_VALUE} for none. Messages before the first user message are always dropped.
   */
  static Fit fit(List<Message> history, int maxMessages, int maxTokens, ToIntFunction<Message> cost) {
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i < history.size(); i++) {
      if (history.get(i).role() == Role.USER) starts.add(i);
    }
    int from = history.size();
    long tokens = 0;
    for (int k = starts.size() - 1; k >= 0; k--) {
      int start = starts.get(k);
      if (maxMessages > 0 && history.size() - start > maxMessages) break;
      long turn = 0;
      for (int i = start; i < from; i++) turn += cost.applyAsInt(history.get(i));
      if (tokens + turn > maxTokens) break;
      tokens += turn;
      from = start;
    }
    return new Fit(List.copyOf(history.subList(from, history.size())), List.copyOf(history.subList(0, from)));
  }
}
