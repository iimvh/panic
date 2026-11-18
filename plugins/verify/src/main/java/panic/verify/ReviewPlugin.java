package panic.verify;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import context.ServiceKey;
import core.Disposable;
import java.util.List;
import java.util.Optional;
import panic.chat.Chat;
import panic.command.Hooks;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.util.Json;
import panic.llm.Llm;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.todo.Todo;

/**
 * An independent evaluator: answers {@link Chat#REVIEW} with a separate model call that judges the final
 * answer against the acceptance criteria of the conversation's plan ({@link Todo#PLAN}). It sees the question,
 * the criteria, what the agent did (tool calls and results) and the answer, under its own prompt, so it does not
 * share the agent's view of its own work.
 *
 * No criteria means no verdict (the answer stands and nothing is spent). A failed model call, or a reply that is
 * not a verdict, throws {@link ReviewException}: chat lets the answer stand but records the failure (an ERROR
 * {@code review} span, a {@link Chat.Reviewed} with the error), so a broken check shows instead of passing
 * silently. Requires {@link Llm#KEY}; a cancelled ask cancels the call.
 */
public final class ReviewPlugin implements ConfigPlugin<ReviewConfig> {

  static final String PROMPT = "You are a strict, independent reviewer. Judge whether the assistant's final answer "
      + "meets every acceptance criterion, using only the evidence in the transcript: tool results and the checks run by the "
      + "harness count, claims the assistant makes without evidence do not. Reply with JSON only: {\"pass\": true or false, \"reason\": "
      + "\"...\"}. When it fails, the reason names each unmet criterion and what is missing, in one or two short "
      + "sentences the assistant can act on.";

  private static final int MESSAGE_CHARS = 2_000;

  @Override
  public List<ServiceKey<?>> requires() {
    return List.of(Llm.KEY);
  }

  @Override
  public Disposable apply(Context ctx, ReviewConfig cfg) {
    Llm llm = ctx.get(Llm.KEY);
    ctx.hook(Chat.REVIEW, review -> judge(ctx, llm, cfg, review));
    return null;
  }

  private static Chat.Verdict judge(Context ctx, Llm llm, ReviewConfig cfg, Chat.Review review) {
    List<String> criteria = criteria(ctx, review.conversationId());
    if (criteria.isEmpty()) return null;
    ChatRequest request = new ChatRequest(List.of(Message.system(cfg.prompt()),
        Message.user(brief(review, criteria, cfg.maxTranscript()))), null, cfg.temperature(), cfg.maxTokens())
        .withReasoningEffort(cfg.reasoningEffort() == null ? ChatRequest.NO_REASONING_EFFORT : cfg.reasoningEffort());
    Completion c;
    try {
      c = llm.complete(request, review.signal());
    } catch (LlmException e) {
      review.signal().throwIfCancelled();
      throw new ReviewException("the reviewer's model call failed: " + e.getMessage(), e);
    }
    Chat.Verdict verdict = verdict(c.text(), c);
    if (verdict == null) {
      String text = c.text() == null ? "" : c.text().strip();
      if (c.truncated()) {
        throw new ReviewException("the reviewer's reply was cut at review.maxTokens (" + cfg.maxTokens() + ") before "
            + "it gave a verdict; a reasoning model spends tokens thinking first: raise review.maxTokens");
      }
      throw new ReviewException("the reviewer gave no verdict: "
          + (text.isEmpty() ? "(empty reply)" : text.length() <= 200 ? text : text.substring(0, 200) + " …"));
    }
    return verdict;
  }

  /** The reviewer could not judge: its model call failed or its reply holds no verdict. */
  public static final class ReviewException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ReviewException(String message) {
      super(message);
    }

    ReviewException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static List<String> criteria(Context ctx, String conversationId) {
    try {
      Optional<Todo.Plan> plan = Hooks.bail(ctx, Todo.PLAN, new Todo.Query(conversationId));
      return plan.map(Todo.Plan::criteria).orElse(List.of());
    } catch (RuntimeException e) {
      return List.of(); // no plan to judge against
    }
  }

  /** What the reviewer reads: the question, the criteria, the work (newest kept) and the answer. */
  static String brief(Chat.Review review, List<String> criteria, int maxTranscript) {
    StringBuilder out = new StringBuilder("Question:\n").append(review.question().strip()).append("\n\nAcceptance criteria:\n");
    for (int i = 0; i < criteria.size(); i++) out.append(i + 1).append(". ").append(criteria.get(i)).append('\n');
    if (!review.evidence().isEmpty()) {
      out.append("\nChecks run by the harness after the answer (ground truth):\n");
      for (String e : review.evidence()) out.append("- ").append(clip(e)).append('\n');
    }
    StringBuilder work = new StringBuilder();
    List<Message> turn = review.turn();
    // the first message is the question and the last one the answer: both are shown on their own
    for (int i = 1; i < turn.size() - 1; i++) {
      Message m = turn.get(i);
      if (m.role() == Role.TOOL) {
        work.append("tool result: ").append(clip(m.content())).append('\n');
      } else if (m.role() == Role.ASSISTANT) {
        if (!m.content().isBlank()) work.append("assistant: ").append(clip(m.content())).append('\n');
        for (ToolCall call : m.toolCalls()) {
          work.append("assistant called ").append(call.name()).append(' ').append(clip(call.arguments())).append('\n');
        }
      } else {
        work.append(m.role().wire()).append(": ").append(clip(m.content())).append('\n');
      }
    }
    if (work.length() > maxTranscript) {
      int cut = work.length() - maxTranscript;
      work.replace(0, cut, "[" + cut + " earlier chars left out]\n");
    }
    out.append("\nWhat the assistant did:\n").append(work.isEmpty() ? "(no tool calls)\n" : work);
    out.append("\nFinal answer:\n").append(review.answer().strip());
    return out.toString();
  }

  /** The verdict in {@code text}: the first JSON object with a boolean {@code pass}; null when there is none. */
  static Chat.Verdict verdict(String text, Completion c) {
    if (text == null) return null;
    int from = text.indexOf('{');
    int to = text.lastIndexOf('}');
    if (from < 0 || to <= from) return null;
    JsonNode n = Json.parseOrNull(text.substring(from, to + 1));
    if (n == null || !n.path("pass").isBoolean()) return null;
    return new Chat.Verdict(n.get("pass").asBoolean(), n.path("reason").asText(null), c.usage());
  }

  private static String clip(String text) {
    if (text.length() <= MESSAGE_CHARS) return text;
    return text.substring(0, MESSAGE_CHARS) + " … [" + (text.length() - MESSAGE_CHARS) + " chars cut]";
  }
}
