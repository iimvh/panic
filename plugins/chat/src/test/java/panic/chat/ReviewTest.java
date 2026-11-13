package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static panic.chat.AgentLoopTest.answer;

import context.Context;
import context.Hook;
import context.Root;
import core.CancelledException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.llm.CancelToken;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.Usage;

/** The chat loop with a reviewer answering {@link Chat#REVIEW}. */
class ReviewTest {

  private final Root root = Context.root("t");
  private final AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
  private final List<Chat.Review> reviews = new CopyOnWriteArrayList<>();
  private final List<Chat.Reviewed> reviewed = new CopyOnWriteArrayList<>();
  private Conversations conversations;

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      Conversation c = new Conversation("c");
      conversations = id -> c;
      ctx.provide(Conversations.KEY, conversations);
      return null;
    });
    root.plugin("audit", ctx -> {
      ctx.on(Chat.REVIEWED, reviewed::add);
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private void chat(Integer maxReviews) {
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0, true, 0, 0, 0, 0, 0, null, null, maxReviews));
  }

  private void reviewer(Hook<Chat.Review, Chat.Verdict> judge) {
    root.plugin("reviewer", ctx -> {
      ctx.hook(Chat.REVIEW, r -> {
        reviews.add(r);
        return judge.handle(r);
      });
      return null;
    });
  }

  /** Verdicts heard through {@link Chat.Ask#onReview}, the way front-ends get them. */
  private final List<Chat.Reviewed> heard = new CopyOnWriteArrayList<>();

  private Chat.Reply ask(StringBuilder streamed) {
    return Chat.ask(root, new Chat.Ask("c", "do it", new CancelToken(), streamed::append, null, heard::add))
        .orElseThrow();
  }

  private static Chat.Verdict reject(String why) {
    return new Chat.Verdict(false, why, new Usage(3, 1));
  }

  @Test
  void withoutAReviewerNothingChanges() {
    chat(null);
    llm.then(r -> answer("done"));
    Chat.Reply reply = ask(new StringBuilder());
    assertEquals(Chat.DONE, reply.stop());
    assertNull(reply.review());
    assertEquals(1, llm.requests.size());
  }

  @Test
  void aRejectedAnswerGetsOneMoreAttempt() {
    chat(null);
    reviewer(r -> r.attempt() == 1 ? reject("tests were not run") : new Chat.Verdict(true, "ok now", Usage.NONE));
    llm.then(r -> answer("first")).then(r -> answer("second"));

    StringBuilder streamed = new StringBuilder();
    Chat.Reply reply = ask(streamed);
    assertEquals("second", reply.text());
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("ok now", reply.review());
    assertEquals(2, reply.steps());
    assertEquals(new Usage(23, 11), reply.usage(), "the review is paid by the turn");
    assertEquals("firstsecond", streamed.toString(), "verdicts are not mixed into the answer's text");
    assertEquals(List.of(false, true), heard.stream().map(r -> r.verdict().pass()).toList());
    assertEquals("tests were not run", heard.get(0).verdict().reason());
    assertEquals(1, heard.get(0).attempt());

    List<Message> retry = llm.requests.get(1).messages();
    Message feedback = retry.get(retry.size() - 1);
    assertEquals(Role.USER, feedback.role());
    assertTrue(feedback.content().contains("tests were not run"), feedback.content());

    Chat.Review first = reviews.get(0);
    assertEquals("do it", first.question());
    assertEquals("first", first.answer());
    assertEquals(List.of(Message.user("do it"), Message.assistant("first")), first.turn());
    assertEquals(2, reviewed.size());

    assertEquals(4, conversations.get("c").size(), "question, first answer, feedback, second answer");
  }

  @Test
  void stopsRejectedOnceRetriesAreUsed() {
    chat(2);
    reviewer(r -> reject("still wrong " + r.attempt()));
    llm.then(r -> answer("again"));
    Chat.Reply reply = ask(new StringBuilder());
    assertEquals(Chat.REJECTED, reply.stop());
    assertEquals("still wrong 3", reply.review());
    assertEquals(3, llm.requests.size(), "the first answer and two retries");
    assertEquals(3, reviews.size());
  }

  @Test
  void zeroReviewsNeverAsks() {
    chat(0);
    reviewer(r -> reject("no"));
    llm.then(r -> answer("done"));
    assertEquals(Chat.DONE, ask(new StringBuilder()).stop());
    assertTrue(reviews.isEmpty());
  }

  @Test
  void noVerdictOrABrokenReviewerAccepts() {
    chat(null);
    reviewer(r -> {
      if (r.attempt() > 0) throw new IllegalStateException("reviewer down");
      return null;
    });
    llm.then(r -> answer("done"));
    Chat.Reply reply = ask(new StringBuilder());
    assertEquals(Chat.DONE, reply.stop());
    assertNull(reply.review());
    assertEquals(1, llm.requests.size());
  }

  @Test
  void cancelDuringAReviewSavesNothing() {
    chat(null);
    CountDownLatch reviewing = new CountDownLatch(1);
    reviewer(r -> {
      reviewing.countDown();
      CountDownLatch never = new CountDownLatch(1);
      r.signal().onCancel(never::countDown);
      try {
        never.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      r.signal().throwIfCancelled();
      return null;
    });
    llm.then(r -> answer("done"));
    CancelToken token = new CancelToken();
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      Thread.ofVirtual().start(() -> {
        try {
          reviewing.await();
        } catch (InterruptedException e) {
          return;
        }
        token.cancel();
      });
      assertThrows(CancelledException.class,
          () -> Chat.ask(root, new Chat.Ask("c", "do it", token, d -> { })));
    });
    assertEquals(0, conversations.get("c").size());
  }
}
