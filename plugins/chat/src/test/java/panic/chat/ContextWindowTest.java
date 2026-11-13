package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;

class ContextWindowTest {

  /** Every message costs 10, so budgets read as message counts times ten. */
  private static int ten(Message m) {
    return 10;
  }

  private static List<Message> plainTurns(int n) {
    List<Message> out = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      out.add(Message.user("q" + i));
      out.add(Message.assistant("a" + i));
    }
    return out;
  }

  /** user, assistant calling two tools, two results, answer: five messages. */
  private static List<Message> toolTurn(String n) {
    return List.of(
        Message.user("q" + n),
        Message.assistant("", List.of(new ToolCall(n + "a", "t", "{}"), new ToolCall(n + "b", "t", "{}"))),
        Message.tool(n + "a", "r"),
        Message.tool(n + "b", "r"),
        Message.assistant("a" + n));
  }

  @Test
  void cutsByTokensNotByCount() {
    List<Message> history = plainTurns(5);
    ContextWindow.Fit fit = ContextWindow.fit(history, 0, 45, ContextWindowTest::ten);
    assertEquals(history.subList(6, 10), fit.kept(), "two turns of 20 fit in 45");
    assertEquals(history.subList(0, 6), fit.dropped());
  }

  @Test
  void bigMessagesCountForMore() {
    List<Message> history = List.of(Message.user("x".repeat(400)), Message.assistant("a"),
        Message.user("q"), Message.assistant("a"));
    Tokens tokens = new Tokens();
    ContextWindow.Fit fit = ContextWindow.fit(history, 0, 50, tokens::estimate);
    assertEquals(history.subList(2, 4), fit.kept());
  }

  @Test
  void toolCallsStayWithTheirResults() {
    List<Message> history = new ArrayList<>();
    history.addAll(toolTurn("1"));
    history.addAll(toolTurn("2"));
    history.addAll(toolTurn("3"));
    for (int budget = 0; budget <= 160; budget += 10) {
      List<Message> kept = ContextWindow.fit(history, 0, budget, ContextWindowTest::ten).kept();
      assertEquals(0, kept.size() % 5, "whole turns only, budget " + budget);
      if (!kept.isEmpty()) assertEquals(Role.USER, kept.get(0).role());
      Set<String> called = new HashSet<>();
      for (Message m : kept) {
        m.toolCalls().forEach(c -> called.add(c.id()));
        if (m.role() == Role.TOOL) assertTrue(called.contains(m.toolCallId()), "result without its call");
      }
    }
  }

  @Test
  void neverStartsBeforeTheFirstUserMessage() {
    List<Message> history = new ArrayList<>(List.of(Message.assistant("orphan")));
    history.addAll(plainTurns(1));
    ContextWindow.Fit fit = ContextWindow.fit(history, 0, Integer.MAX_VALUE, ContextWindowTest::ten);
    assertEquals(history.subList(1, 3), fit.kept());
    assertEquals(List.of(Message.assistant("orphan")), fit.dropped());
  }

  @Test
  void stopsAtTheFirstTurnThatDoesNotFit() {
    List<Message> history = List.of(Message.user("small"), Message.assistant("a"),
        Message.user("x".repeat(4_000)), Message.assistant("a"),
        Message.user("q"), Message.assistant("a"));
    ContextWindow.Fit fit = ContextWindow.fit(history, 0, 100, new Tokens()::estimate);
    assertEquals(history.subList(4, 6), fit.kept(), "no hole: the small first turn is not picked up");
  }

  @Test
  void countLimitStillApplies() {
    List<Message> history = plainTurns(5);
    assertEquals(history.subList(8, 10), ContextWindow.fit(history, 3, Integer.MAX_VALUE,
        ContextWindowTest::ten).kept(), "starts on a user message, like Conversation.recent");
    assertEquals(List.of(), ContextWindow.fit(history, 0, 0, ContextWindowTest::ten).kept());
  }
}
