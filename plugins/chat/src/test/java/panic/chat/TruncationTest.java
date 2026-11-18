package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static panic.chat.AgentLoopTest.answer;
import static panic.chat.AgentLoopTest.call;
import static panic.chat.AgentLoopTest.calls;

import context.Context;
import context.Root;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.Usage;
import panic.tools.Tools;
import panic.util.CancelToken;

/**
 * A reply cut at the output limit ({@code finish_reason: length}) is neither an answer to review nor calls to run:
 * the model is told and goes on, and only cuts in a row stop the turn.
 */
class TruncationTest {

  private final Root root = Context.root("t");
  private final AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
  private final List<Chat.Review> reviews = new CopyOnWriteArrayList<>();
  private final List<String> ran = new CopyOnWriteArrayList<>();
  private Conversation conversation;

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      conversation = new Conversation("c");
      ctx.provide(Conversations.KEY, id -> conversation);
      return null;
    });
    root.plugin("tools-under-test", ctx -> {
      Tools.register(ctx, AgentLoopTest.ECHO);
      ctx.on(Chat.STEP, s -> {
        if (s.done()) ran.add(s.call().name());
      });
      return null;
    });
    root.plugin("reviewer", ctx -> {
      ctx.hook(Chat.REVIEW, r -> {
        reviews.add(r);
        return new Chat.Verdict(true, "fine", Usage.NONE);
      });
      return null;
    });
  }

  private void chat(int maxSteps) {
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0, true, maxSteps, 0, 0, 0, 0, null, null, 1));
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private static Completion cut(String text, ToolCall... partial) {
    return new Completion(text, "m", "length", new Usage(10, 5), List.of(partial));
  }

  private Chat.Reply ask(StringBuilder streamed) {
    return Chat.ask(root, new Chat.Ask("c", "write the blog", new CancelToken(), streamed::append)).orElseThrow();
  }

  @Test
  void aCutReplyIsNotReviewedNorRunAndTheModelIsTold() {
    llm.then(r -> cut("I'll write the data now", call("a", "echo", "{\"text\":\"lo")))
        .then(r -> calls(call("b", "echo", "{\"text\":\"short\"}")))
        .then(r -> answer("done"));
    chat(0);
    Chat.Reply reply = ask(new StringBuilder());

    assertEquals(List.of("echo"), ran, "the incomplete call never ran, the next one did");
    assertEquals(1, reviews.size(), "the cut reply was not taken for an answer");
    assertEquals("done", reviews.get(0).answer());

    List<Message> retry = llm.requests.get(1).messages();
    Message cutReply = retry.get(retry.size() - 2);
    assertEquals(Role.ASSISTANT, cutReply.role());
    assertEquals("I'll write the data now", cutReply.content());
    assertTrue(cutReply.toolCalls().isEmpty(), "a call without its result would be refused by providers");
    Message feedback = retry.get(retry.size() - 1);
    assertEquals(Role.USER, feedback.role());
    assertTrue(feedback.content().startsWith(Chat.TRUNCATED_FEEDBACK_PREFIX), feedback.content());
    assertTrue(feedback.content().contains("call to echo: nothing was run"), feedback.content());
    assertTrue(Chat.fromHarness(feedback.content()));

    assertEquals(Chat.DONE, reply.stop());
    assertEquals("done", reply.text());
    assertTrue(conversation.snapshot().messages().stream()
        .allMatch(m -> m.role() != Role.ASSISTANT || m.toolCalls().stream().allMatch(c -> !c.id().equals("a"))));
  }

  @Test
  void cutsInARowStopTheTurnWithANote() {
    chat(0);
    llm.then(r -> cut("part"));
    StringBuilder streamed = new StringBuilder();
    Chat.Reply reply = ask(streamed);

    assertEquals(Chat.TRUNCATED, reply.stop());
    assertEquals(Chat.TRUNCATIONS_IN_A_ROW, llm.requests.size());
    assertTrue(reviews.isEmpty(), "nothing finished, so nothing was judged");
    assertTrue(reply.text().startsWith("Stopped because the model's reply was cut at the output token limit 3 times"),
        reply.text());
    assertTrue(reply.text().contains("llm.maxTokens"), reply.text());
    assertTrue(streamed.toString().endsWith("\n\n" + reply.text()), "the note follows what was streamed");
    // the work is saved, the note is not
    List<Message> saved = conversation.snapshot().messages();
    assertEquals(Message.assistant("part"), saved.get(saved.size() - 1));
    assertFalse(saved.stream().anyMatch(m -> m.content().startsWith("Stopped")));
  }

  @Test
  void onlyCutsInARowCount() {
    chat(0);
    llm.then(r -> cut("a"))
        .then(r -> cut("b"))
        .then(r -> calls(call("x", "echo", "{\"text\":\"ok\"}")))
        .then(r -> cut("c"))
        .then(r -> cut("d"))
        .then(r -> calls(call("y", "echo", "{\"text\":\"ok2\"}")))
        .then(r -> cut("e"))
        .then(r -> cut("f"))
        .then(r -> cut("g"));
    Chat.Reply reply = ask(new StringBuilder());
    assertEquals(Chat.TRUNCATED, reply.stop());
    assertEquals(9, reply.steps());
    assertEquals(List.of("echo", "echo"), ran);
  }

  @Test
  void theStepLimitStillHolds() {
    chat(2);
    llm.then(r -> cut("x"));
    Chat.Reply reply = ask(new StringBuilder());
    assertEquals(Chat.MAX_STEPS, reply.stop());
    assertEquals(2, llm.requests.size());
  }
}
