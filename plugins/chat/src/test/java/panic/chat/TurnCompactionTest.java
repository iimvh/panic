package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;

/** Where {@link TurnCompaction} cuts a turn and what the turn looks like afterwards. */
class TurnCompactionTest {

  /** One token per character. */
  private static final ContextEditor.Estimator CHARS = m -> m.content().length();

  /** {@code n} steps of one call with two results each (parallel calls), each result {@code size} chars. */
  private static List<Message> steps(int n, int size) {
    List<Message> out = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      out.add(Message.assistant("", List.of(new ToolCall("a" + i, "t", "{}"), new ToolCall("b" + i, "t", "{}"))));
      out.add(Message.tool("a" + i, "x".repeat(size)));
      out.add(Message.tool("b" + i, "y".repeat(size)));
    }
    return out;
  }

  @Test
  void cutsBeforeAnAssistantMessageKeepingWhatFits() {
    List<Message> steps = steps(10, 100); // 200 per step
    int cut = TurnCompaction.cut(steps, CHARS, 450);
    assertEquals(Role.ASSISTANT, steps.get(cut).role());
    assertEquals(24, cut, "the last two steps (400) fit 450, three would not");
  }

  @Test
  void theNewestStepIsKeptEvenWhenItAloneIsLarger() {
    List<Message> steps = steps(3, 1_000);
    assertEquals(6, TurnCompaction.cut(steps, CHARS, 10));
  }

  @Test
  void nothingToCutWithASingleStep() {
    assertEquals(0, TurnCompaction.cut(steps(1, 100), CHARS, 10));
    assertEquals(0, TurnCompaction.cut(List.of(), CHARS, 10));
  }

  @Test
  void theViewKeepsTheHeadAndJoinsTheProgressToTheQuestion() {
    List<Message> turn = new ArrayList<>(List.of(Message.user("question")));
    turn.addAll(steps(4, 10));
    assertSame(turn, TurnCompaction.view(turn, 1, 0, "p"), "nothing summarised");
    List<Message> view = TurnCompaction.view(turn, 1, 7, "progress");
    assertEquals(Message.user("question\n\nprogress"), view.get(0));
    assertEquals(turn.subList(7, turn.size()), view.subList(1, view.size()));
  }

  @Test
  void aHeadThatEndsWithAToolResultGetsTheProgressAsItsOwnMessage() {
    // a slash prompt recorded as a call: question, the call, its result
    List<Message> turn = new ArrayList<>(List.of(Message.user("/skill"),
        Message.assistant("", List.of(new ToolCall("pre_1", "skill", "{}"))), Message.tool("pre_1", "body")));
    turn.addAll(steps(3, 10));
    List<Message> view = TurnCompaction.view(turn, 3, 6, "progress");
    assertEquals(turn.subList(0, 3), view.subList(0, 3));
    assertEquals(Message.user("progress"), view.get(3));
    assertEquals(Role.ASSISTANT, view.get(4).role());
  }

  @Test
  void theProgressHasTheHeadingTheSummaryAndTheManifest() {
    String p = TurnCompaction.progress("Goal: x", "Files read: a.js");
    assertTrue(p.startsWith(TurnCompaction.HEADING));
    assertTrue(p.contains("\n\nGoal: x\n\nFiles read: a.js"), p);
    assertEquals(TurnCompaction.HEADING + "\n\nFiles read: a.js", TurnCompaction.progress(null, "Files read: a.js"));
  }
}
