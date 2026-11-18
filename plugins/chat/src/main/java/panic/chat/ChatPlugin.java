package panic.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import context.ConfigPlugin;
import context.Context;
import context.EmitException;
import context.ServiceKey;
import context.Task;
import core.CancelledException;
import core.Disposable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.Out;
import panic.command.Prompts;
import panic.util.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.util.Json;
import panic.llm.Llm;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.trace.OpenSpan;
import panic.trace.Trace;

/**
 * The chat bot, an agent loop: answers {@link Chat#ASK} with the current LLM, offering it the tools registered
 * right now ({@code Tools.specs}). When the model asks for tools it runs them, feeds the results back (errors
 * included, so the model can recover) and calls the model again, until it answers or a limit of
 * {@link ChatConfig} stops it. History is kept in {@link Conversations}, the whole turn appended at once.
 *
 * Each request is kept under {@link ChatConfig#contextTokens}: the system prompt (with the sections other
 * plugins add through {@link Chat#CONTEXT}) and the current turn are always sent, the newest earlier turns that
 * fit come after the prompt. When turns no longer fit, they are summarised by the model once into the
 * conversation's summary (see {@link Conversation#compact}), down to half the budget so that this does not
 * happen on every ask.
 *
 * A final answer is put to {@link Chat#REVIEW} when someone answers it (the reviewer of {@code plugins/verify}
 * does when the conversation has acceptance criteria). A rejection is streamed to the asker and sent back to the
 * model as a user message, at most {@link ChatConfig#maxReviews} times; a reviewer that fails accepts, with an
 * ERROR {@code review} span and a {@link Chat.Reviewed} that carries the error.
 *
 * Every turn is traced: a root span {@code turn} with children {@code llm} (one per model call), {@code tool},
 * {@code review} and {@code compaction}, emitted on {@link Trace#SPAN} as each ends, whether the turn is done,
 * fails or is cancelled. Nobody has to listen; a failing listener never affects the turn.
 *
 * Runs only while both services exist; tools are optional and found through hooks, so none are required. A
 * reply in flight when the plugin is disposed (LLM switched, config changed, app stopping) is cancelled, its
 * tool calls included, and the disposal waits for it to return; a cancelled turn is not saved.
 *
 * Command: {@code ask <text...>}, in the session's conversation. It exists only while an LLM is connected.
 */
public final class ChatPlugin implements ConfigPlugin<ChatConfig> {

  /** Writes arguments with sorted keys, so {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} are the same call. */
  private static final ObjectMapper CANONICAL = Json.MAPPER.copy()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  @Override
  public List<ServiceKey<?>> requires() {
    return List.of(Llm.KEY, Conversations.KEY);
  }

  @Override
  public Disposable apply(Context ctx, ChatConfig cfg) {
    Llm llm = ctx.get(Llm.KEY);
    Conversations conversations = ctx.get(Conversations.KEY);
    Tokens tokens = new Tokens();
    ctx.hook(Chat.ASK, ask -> new Turn(ctx, cfg, llm, conversations, tokens, ask).run());
    Commands.command(ctx, "ask <text...>", "ask the bot (plain text does the same)", call -> {
      Printer printer = new Printer(call.out());
      Chat.Ask ask = new Chat.Ask(call.session().id(), call.arg("text"), call.signal(), printer::text,
          printer::step, printer::review);
      Chat.Reply r = new Turn(ctx, cfg, llm, conversations, tokens, ask).run();
      printer.endLine();
      StringBuilder meta = new StringBuilder("[").append(r.model());
      if (r.usage().totalTokens() > 0) meta.append(" · ").append(r.usage().totalTokens()).append(" tokens");
      if (r.toolCalls() > 0) meta.append(" · ").append(r.toolCalls()).append(r.toolCalls() == 1 ? " tool" : " tools");
      if (!Chat.DONE.equals(r.stop())) {
        meta.append(" · stopped: ").append(r.stop());
        if (Chat.REJECTED.equals(r.stop())) meta.append(" (").append(r.review()).append(')');
      } else if (r.finishReason() != null && !"stop".equals(r.finishReason())) {
        meta.append(" · ").append(r.finishReason());
      }
      call.out().println(meta.append("]").toString());
    });
    return null;
  }

  /**
   * Tells the model how to summarise the turns that no longer fit. The sections follow Claude Code's compaction
   * prompt: the user's requests are kept word for word, so a long task does not lose what was asked.
   */
  static final String SUMMARY_PROMPT = "You keep the memory of a conversation between a user and an assistant "
      + "that uses tools. Write a summary of the conversation below for the assistant to continue from, with "
      + "these sections:\n"
      + "1. Primary request and intent: what the user asked for, in detail.\n"
      + "2. User messages: every message the user wrote (not tool results), word for word, in order.\n"
      + "3. Key facts: names, file paths, commands, values and results of tool calls that are still needed.\n"
      + "4. Work done and current state: what was changed or decided, and what was verified (and how).\n"
      + "5. Pending tasks and the next step.\n"
      + "Start from the summary so far when there is one, and keep its user messages. Leave out greetings and "
      + "anything not needed later. Do not invent anything. Answer with the summary only.";

  /** Heading of the user messages that {@link #verbatim} adds when the summary left them out. */
  static final String VERBATIM_HEADING = "User messages (word for word)";

  /** What a cleared tool result says instead (Anthropic's context editing replaces them with a placeholder too). */
  static final String CLEARED = "[tool result cleared to save context (%d chars); call the tool again if you need it]";

  /** Heading of the summary in the system prompt. */
  static final String SUMMARY_HEADING = "Summary of the earlier conversation";

  /** Characters of one message shown to the summariser; tool output beyond this is rarely worth keeping. */
  private static final int SUMMARY_MESSAGE_CHARS = 2_000;

  /** Characters of the question (and of a review's reason) kept on a span: enough to recognise it. */
  private static final int SPAN_TEXT_CHARS = 200;

  /**
   * {@code summary} with every user message of {@code turns} it does not quote added word for word under
   * {@link #VERBATIM_HEADING}: the model is asked to keep them, and this makes sure it did. A reviewer's
   * feedback is the harness speaking, not the user, and is left out.
   */
  static String verbatim(String summary, List<Message> turns) {
    StringBuilder missing = new StringBuilder();
    for (Message m : turns) {
      if (m.role() != Role.USER || m.content().startsWith(Chat.REVIEW_FEEDBACK_PREFIX)) continue;
      String text = clip(m.content().strip());
      if (text.isEmpty() || summary.contains(text)) continue;
      missing.append("- ").append(text.replace("\n", "\n  ")).append('\n');
    }
    if (missing.isEmpty()) return summary;
    return summary + "\n\n" + VERBATIM_HEADING + ":\n" + missing.toString().stripTrailing();
  }

  /**
   * The last turn of {@code turns} reduced to its question and its final answer (no tool calls, which the summary
   * covers); empty when it has no answer in words.
   */
  static List<Message> condensed(List<Message> turns) {
    int start = -1;
    for (int i = turns.size() - 1; i >= 0; i--) {
      if (turns.get(i).role() == Role.USER) {
        start = i;
        break;
      }
    }
    if (start < 0) return List.of();
    Message answer = null;
    for (int i = turns.size() - 1; i > start; i--) {
      Message m = turns.get(i);
      if (m.role() == Role.ASSISTANT && m.toolCalls().isEmpty() && !m.content().isBlank()) {
        answer = m;
        break;
      }
    }
    return answer == null ? List.of() : List.of(turns.get(start), Message.assistant(answer.content()));
  }

  /**
   * Replaces, in {@code messages}, every tool result but the newest {@code keep} by a {@link #CLEARED}
   * placeholder, as Anthropic's {@code clear_tool_uses} context edit does: old file contents and command outputs
   * are what fills a long turn, and the model can call the tool again. Results already short are left as they
   * are. Returns how many were cleared.
   */
  static int clearToolResults(List<Message> messages, int keep) {
    int seen = 0;
    int count = 0;
    for (int i = messages.size() - 1; i >= 0; i--) {
      Message m = messages.get(i);
      if (m.role() != Role.TOOL) continue;
      if (++seen <= keep) continue;
      String placeholder = String.format(java.util.Locale.ROOT, CLEARED, m.content().length());
      if (m.content().length() <= placeholder.length()) continue;
      messages.set(i, Message.tool(m.toolCallId(), placeholder));
      count++;
    }
    return count;
  }

  static String clip(String text) {
    if (text.length() <= SUMMARY_MESSAGE_CHARS) return text;
    return text.substring(0, SUMMARY_MESSAGE_CHARS) + " … [" + (text.length() - SUMMARY_MESSAGE_CHARS)
        + " chars cut]";
  }


  /** One question, from the user's message to the bot's answer. */
  private static final class Turn {

    private final Context ctx;
    private final ChatConfig cfg;
    private final Llm llm;
    private final Conversations conversations;
    private final Tokens tokens;
    private final Chat.Ask ask;
    private final CancelToken signal;

    /** System prompt and contributed sections, without the summary. Fixed for the whole turn. */
    private String prompt;
    private String summary;
    private List<Message> history = List.of();
    private final List<Message> added = new ArrayList<>();
    private Usage usage = Usage.NONE;
    private int toolCalls;
    /** How many times each tool ran this turn, in first-call order, for the closing text of a stopped turn. */
    private final java.util.Map<String, Integer> ran = new java.util.LinkedHashMap<>();
    private String lastCall;
    private int repeats;
    private int reviews;
    private String review;
    private int steps;
    private String stop = Chat.DONE;
    private String model;
    private boolean saved;
    /** Guards the asker's callbacks while tool calls run in parallel. */
    private final Object callbacks = new Object();
    /** The root span of this turn's trace; open from {@link #run()} to its end. */
    private OpenSpan span;

    Turn(Context ctx, ChatConfig cfg, Llm llm, Conversations conversations, Tokens tokens, Chat.Ask ask) {
      this.ctx = ctx;
      this.cfg = cfg;
      this.llm = llm;
      this.conversations = conversations;
      this.tokens = tokens;
      this.ask = ask;
      this.signal = new CancelToken();
    }

    /** Cancelled by the asker or by the plugin's disposal; the links are removed when the turn ends. */
    Chat.Reply run() {
      Disposable byAsker = ask.signal().onCancel(signal::cancel);
      Disposable byPlugin = ctx.signal().onCancel(signal::cancel);
      Chat.Delegate delegate = ask.delegate();
      span = Trace.open(ctx, ask.traceId(), delegate == null ? null : delegate.parentSpanId(), "turn")
          .attr(Trace.CONVERSATION, ask.conversationId())
          .attr("question", cut(ask.text()))
          .attr(Trace.QUESTION_HASH, Trace.questionHash(ask.text()));
      if (delegate != null) span.attr("agent", delegate.agent()).attr("origin", delegate.origin());
      try {
        Chat.Reply reply = loop();
        summarise();
        span.end();
        return reply;
      } catch (RuntimeException | Error e) {
        summarise();
        fail(span, e);
        throw e;
      } finally {
        byPlugin.dispose();
        byAsker.dispose();
      }
    }

    private Chat.Reply loop() {
      Conversation conversation = conversations.get(ask.conversationId());
      if (ask.delegate() != null) conversation.parent(ask.delegate().origin()); // kept out of listings
      prompt = prompt();
      Conversation.Snapshot snapshot = conversation.snapshot();
      summary = snapshot.summary();
      history = snapshot.messages();
      add(Message.user(ask.text()));
      if (ask.delegate() == null) expand(); // a sub-agent's task is the model's text: it never triggers a prompt
      // a sub-agent's conversation lives for one task: nothing to gain from summarising it
      if (cfg.limitsContext() && cfg.compact() && ask.delegate() == null) compact(conversation);

      Completion last;
      while (true) {
        steps++;
        ChatRequest request = request();
        last = callModel(request);
        model = last.model();
        tokens.observe(Tokens.raw(request), last.usage().promptTokens());
        count(last.usage());
        add(last.message());
        if (!last.hasToolCalls()) {
          Chat.Verdict verdict = review(last.text());
          if (verdict == null || verdict.pass()) break;
          if (reviews >= cfg.maxReviews() || steps >= maxSteps()) {
            stop = Chat.REJECTED;
            break;
          }
          reviews++;
          add(Message.user(Chat.REVIEW_FEEDBACK_PREFIX + verdict.reason()
              + "\nFix what is missing (use tools if needed), then give your final answer again."));
          continue;
        }

        if (steps >= maxSteps()) {
          stop = Chat.MAX_STEPS;
        } else if (maxTokens() > 0 && usage.totalTokens() >= maxTokens()) {
          stop = Chat.BUDGET;
        }
        stop = runCalls(last.toolCalls());
        if (!Chat.DONE.equals(stop)) break;
      }

      signal.throwIfCancelled();
      conversation.append(added.toArray(Message[]::new));
      saved = true;
      String text = last.text();
      String closing = closing(text);
      if (closing != null) {
        // the harness's own words: shown to the user, never saved as something the model said
        ask.onDelta().accept(closing);
        text = closing;
      }
      Chat.Reply reply = new Chat.Reply(text, last.model(), last.finishReason(), usage, stop, steps,
          toolCalls, review, span.traceId());
      ctx.emit(Chat.TURN, new Chat.Turn(ask.conversationId(), ask.text(), reply, ask.delegate()));
      return reply;
    }

    /**
     * What the user reads when a hard limit stopped the turn before the model answered (the role of the OpenAI
     * Agents SDK's max_turns handler): why it stopped, what ran, and how to go on (Cursor's "Continue"). Null when
     * the model answered, the turn finished, or for a sub-agent (its caller reports the stop).
     */
    private String closing(String answer) {
      if (ask.delegate() != null || !(Chat.MAX_STEPS.equals(stop) || Chat.BUDGET.equals(stop))) return null;
      if (answer != null && !answer.isBlank()) return null;
      String why = Chat.MAX_STEPS.equals(stop) ? "after " + steps + " steps (max_steps)"
          : "after using " + usage.totalTokens() + " tokens (budget)";
      StringBuilder out = new StringBuilder("Stopped ").append(why).append(" before finishing.");
      if (!ran.isEmpty()) {
        out.append(" Tools run this turn: ");
        String sep = "";
        for (java.util.Map.Entry<String, Integer> e : ran.entrySet()) {
          out.append(sep).append(e.getKey()).append(" ×").append(e.getValue());
          sep = ", ";
        }
        out.append('.');
      }
      return out.append(" Say \"continue\" to go on from here.").toString();
    }

    /** The step limit: the sub-agent's own when it has one, otherwise the config's. */
    private int maxSteps() {
      Chat.Delegate d = ask.delegate();
      return d != null && d.maxSteps() > 0 ? d.maxSteps() : cfg.maxSteps();
    }

    /** The token budget (0 = none): the sub-agent's own when it has one, otherwise the config's. */
    private int maxTokens() {
      Chat.Delegate d = ask.delegate();
      return d != null && d.maxTokens() > 0 ? d.maxTokens() : cfg.maxTokens();
    }

    /**
     * The tools offered to the model: none when tools are off, those no {@code Tools.GATE} refuses in the
     * conversation the user talks to (plan mode, say), and only the allowed ones for a sub-agent. Asked again for
     * every request, so a mode that ends mid-turn offers the tools on the next model call.
     */
    private List<ToolSpec> specs() {
      if (!cfg.tools()) return List.of();
      Chat.Delegate d = ask.delegate();
      List<ToolSpec> all = Tools.specs(ctx, d == null ? ask.conversationId() : d.origin());
      return d == null ? all : all.stream().filter(t -> d.allows(t.name())).toList();
    }

    /**
     * One model call, streamed to the asker, as an {@code llm} span. {@code firstTokenMicros} is when the first
     * piece of text or of a tool call arrived: the wait the user sees.
     */
    private Completion callModel(ChatRequest request) {
      OpenSpan call = span.child("llm").attr("step", steps).attr("messages", request.messages().size());
      if (cleared > 0) call.attr("clearedToolResults", cleared);
      long[] first = {-1};
      Runnable mark = () -> {
        if (first[0] < 0) first[0] = call.elapsedMicros();
      };
      Completion c;
      try {
        c = llm.stream(request, signal, d -> {
          mark.run();
          ask.onDelta().accept(d);
        }, d -> {
          mark.run();
          ask.onToolDelta().accept(d);
        });
      } catch (RuntimeException e) {
        fail(call, e);
        throw e;
      }
      if (first[0] >= 0) call.attr("firstTokenMicros", first[0]);
      call.attr("model", c.model()).attr("finishReason", c.finishReason())
          .attr("promptTokens", c.usage().promptTokens()).attr("completionTokens", c.usage().completionTokens())
          .attr("toolCalls", c.toolCalls().size())
          .end();
      return c;
    }

    /** The turn's totals on its root span, set however it ends. */
    private void summarise() {
      span.attr("model", model).attr("stop", stop).attr("steps", steps).attr("toolCalls", toolCalls)
          .attr("promptTokens", usage.promptTokens()).attr("completionTokens", usage.completionTokens())
          .attr(Trace.SAVED, saved);
    }

    /** Ends {@code s} as failed; anything thrown once the turn was cancelled counts as the cancellation. */
    private void fail(OpenSpan s, Throwable e) {
      s.fail(signal.isCancelled() && !(e instanceof CancelledException) ? new CancelledException("cancelled") : e);
    }

    private static String cut(String text) {
      return text.length() <= SPAN_TEXT_CHARS ? text : text.substring(0, SPAN_TEXT_CHARS) + " …";
    }

    /**
     * Puts the final {@code answer} to {@link Chat#REVIEW}. Null when reviews are off or nobody judged it; a
     * reviewer that fails counts as no verdict, so a broken reviewer never blocks an answer, but its span ends as
     * ERROR and a {@link Chat.Reviewed} with the error is reported. Cancellation is thrown.
     */
    private Chat.Verdict review(String answer) {
      if (cfg.maxReviews() == 0 || ask.delegate() != null) return null; // the asker's answer is the one reviewed
      int attempt = reviews + 1;
      Chat.Review question = new Chat.Review(ask.conversationId(), ask.text(), added, answer, attempt, signal);
      List<Chat.StopCheck> checks = stopChecks(question);
      List<String> blocks = checks.stream().filter(Chat.StopCheck::block)
          .map(c -> c.name() + ": " + c.reason()).toList();
      if (!blocks.isEmpty()) {
        Chat.Verdict blocked = new Chat.Verdict(false, "stop hook " + String.join("; stop hook ", blocks), Usage.NONE);
        review = blocked.reason();
        Chat.Reviewed reviewed = new Chat.Reviewed(ask.conversationId(), attempt, blocked);
        ask.onReview().accept(reviewed);
        ctx.emit(Chat.REVIEWED, reviewed);
        return blocked;
      }
      List<String> evidence = checks.stream().filter(c -> c.error() == null)
          .map(c -> c.name() + ": " + c.reason()).toList();
      OpenSpan s = span.child("review").attr("attempt", attempt);
      Optional<Chat.Verdict> verdict;
      try {
        verdict = Hooks.bail(ctx, Chat.REVIEW, question.withEvidence(evidence));
      } catch (RuntimeException e) {
        fail(s, e);
        signal.throwIfCancelled();
        if (e instanceof CancelledException c) throw c;
        // the answer stands, but a check that could not run must show, not pass silently
        String why = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        Chat.Reviewed failed = new Chat.Reviewed(ask.conversationId(), attempt, null, why);
        ask.onReview().accept(failed);
        ctx.emit(Chat.REVIEWED, failed);
        return null;
      }
      if (signal.isCancelled()) {
        fail(s, new CancelledException("cancelled"));
        signal.throwIfCancelled();
      }
      if (verdict.isEmpty()) {
        s.discard(); // nobody judged: no review happened
        return null;
      }
      Usage cost = verdict.get().usage();
      s.attr("verdict", verdict.get().pass() ? "pass" : "fail").attr("pass", verdict.get().pass())
          .attr("reason", cut(verdict.get().reason()))
          .attr("promptTokens", cost.promptTokens()).attr("completionTokens", cost.completionTokens())
          .end();
      count(verdict.get().usage());
      review = verdict.get().reason();
      Chat.Reviewed reviewed = new Chat.Reviewed(ask.conversationId(), attempt, verdict.get());
      ask.onReview().accept(reviewed);
      ctx.emit(Chat.REVIEWED, reviewed);
      return verdict.get();
    }

    /**
     * Collects {@link Chat#STOP} for {@code question}, under one {@code stop} span (none when nobody answers). A
     * check that could not run is reported to the asker like a failed review; cancellation is thrown.
     */
    private List<Chat.StopCheck> stopChecks(Chat.Review question) {
      OpenSpan s = span.child("stop").attr("attempt", question.attempt());
      List<Chat.StopCheck> checks = new ArrayList<>();
      List<String> errors = new ArrayList<>();
      try {
        checks.addAll(ctx.collect(Chat.STOP, question));
      } catch (EmitException e) {
        for (EmitException.Failure f : e.failures()) {
          if (f.error() instanceof CancelledException c) {
            fail(s, c);
            throw c;
          }
          errors.add(f.source() + ": " + f.error().getMessage());
        }
      }
      if (signal.isCancelled()) {
        fail(s, new CancelledException("cancelled"));
        signal.throwIfCancelled();
      }
      checks.stream().filter(c -> c.error() != null).forEach(c -> errors.add(c.name() + ": " + c.error()));
      if (checks.isEmpty() && errors.isEmpty()) {
        s.discard();
        return checks;
      }
      long blocked = checks.stream().filter(Chat.StopCheck::block).count();
      s.attr("checks", checks.size()).attr("blocked", blocked)
          .attr("names", String.join(", ", checks.stream().map(Chat.StopCheck::name).toList()));
      if (blocked > 0) {
        s.attr("reason", cut(checks.stream().filter(Chat.StopCheck::block).map(Chat.StopCheck::reason)
            .findFirst().orElse("")));
      }
      for (String error : errors) {
        Chat.Reviewed failed = new Chat.Reviewed(ask.conversationId(), question.attempt(), null, "stop hook " + error);
        ask.onReview().accept(failed);
        ctx.emit(Chat.REVIEWED, failed);
      }
      if (errors.isEmpty()) s.end();
      else s.fail(String.join("; ", errors));
      return checks;
    }

    /**
     * The request for the next model call: system message, the earlier turns that fit, then the whole current
     * turn. Built again for every call, as the turn grows and tools come and go.
     */
    private ChatRequest request() {
      List<ToolSpec> specs = specs();
      Message system = system();
      List<Message> messages = new ArrayList<>();
      if (system != null) messages.add(system);
      messages.addAll(fit(system, specs, 1, cfg.maxHistory()).kept());
      messages.addAll(added);
      cleared = 0;
      if (cfg.clearToolResultsAt() > 0) {
        long estimate = tokens.estimate(messages) + (long) tokens.estimateTools(specs);
        if (estimate > cfg.clearToolResultsAt()) cleared = clearToolResults(messages, cfg.keepToolResults());
      }
      ChatRequest request = ChatRequest.of(messages);
      return specs.isEmpty() ? request : request.withTools(specs, null);
    }

    /** Tool results the last request sent as placeholders (for the {@code llm} span). */
    private int cleared;


    /**
     * The earlier turns that fit {@code share} of what the budget leaves after the system message, the tools
     * and the current turn (which are always sent, even over budget), and at most {@code maxMessages} (0 = any).
     */
    private ContextWindow.Fit fit(Message system, List<ToolSpec> specs, double share, int maxMessages) {
      int budget = Integer.MAX_VALUE;
      if (cfg.limitsContext()) {
        long fixed = (system == null ? 0 : tokens.estimate(system)) + (long) tokens.estimateTools(specs)
            + tokens.estimate(added);
        budget = (int) Math.max(0, (cfg.contextTokens() - fixed) * share);
      }
      return ContextWindow.fit(history, maxMessages, budget, tokens::estimate);
    }

    /**
     * When earlier turns no longer fit the token budget, summarises the ones that do not fit half the budget, with the
     * previous summary, into a new summary kept with the conversation. Halving leaves room to grow, so the next
     * asks do not each pay for a summary. If the model fails, the turns are only left out of this request; a
     * cancelled ask is thrown. {@code maxHistory} plays no part here: it only caps what is sent.
     */
    private void compact(Conversation conversation) {
      List<ToolSpec> specs = specs();
      if (fit(system(), specs, 1, 0).dropped().isEmpty()) return;
      ContextWindow.Fit target = fit(system(), specs, 0.5, 0);
      OpenSpan s = span.child("compaction").attr("messages", target.dropped().size())
          .attr("kept", target.kept().size());
      String next;
      try {
        next = summarize(target.dropped(), s);
      } catch (RuntimeException e) {
        fail(s, e);
        throw e;
      }
      if (next != null) next = verbatim(next, target.dropped());
      // never start from nothing: when not even the last turn fits, keep its question and answer
      List<Message> kept = target.kept().isEmpty() ? condensed(target.dropped()) : List.of();
      boolean applied = next != null && conversation.compact(target.dropped(), next, kept);
      if (applied) {
        summary = next;
        history = target.kept().isEmpty() ? kept : target.kept();
      }
      s.attr("condensed", !kept.isEmpty());
      s.attr("applied", applied);
      if (next == null) s.fail("no summary; the turns are only left out of this request");
      else s.end();
    }

    /** The new summary, or null when the model failed (noted on {@code s}); a cancelled ask is thrown. */
    private String summarize(List<Message> turns, OpenSpan s) {
      int maxTokens = Math.max(256, Math.min(2_048, cfg.contextTokens() / 8));
      ChatRequest request = new ChatRequest(
          List.of(Message.system(SUMMARY_PROMPT), Message.user(transcript(turns))), null, null, maxTokens);
      try {
        Completion c = llm.complete(request, signal);
        count(c.usage());
        s.attr("model", c.model()).attr("promptTokens", c.usage().promptTokens())
            .attr("completionTokens", c.usage().completionTokens());
        String text = c.text().strip();
        return text.isEmpty() ? null : text;
      } catch (LlmException e) {
        signal.throwIfCancelled();
        s.attr("error", e.getMessage());
        return null;
      }
    }

    /**
     * The turns to summarise as plain text, after the previous summary. Kept under about half the context
     * budget: long messages are cut, and when that is not enough the oldest part goes.
     */
    private String transcript(List<Message> turns) {
      StringBuilder out = new StringBuilder();
      if (summary != null) out.append("Summary so far:\n").append(summary).append("\n\n");
      out.append("Conversation to add:\n");
      for (Message m : turns) {
        String content = clip(m.content());
        switch (m.role()) {
          case USER -> out.append("user: ").append(content).append('\n');
          case ASSISTANT -> {
            if (!content.isBlank()) out.append("assistant: ").append(content).append('\n');
            for (ToolCall call : m.toolCalls()) {
              out.append("assistant called ").append(call.name()).append(' ').append(clip(call.arguments()))
                  .append('\n');
            }
          }
          case TOOL -> out.append("tool result: ").append(content).append('\n');
          default -> out.append(m.role().wire()).append(": ").append(content).append('\n');
        }
      }
      long max = Math.max(8_000L, cfg.contextTokens() * 2L);
      if (out.length() > max) {
        int cut = (int) (out.length() - max);
        return "[" + cut + " older chars left out]\n" + out.substring(cut);
      }
      return out.toString();
    }


    /** The configured prompt followed by the sections contributed through {@link Chat#CONTEXT}. */
    private String prompt() {
      List<String> parts = new ArrayList<>();
      String base = ask.delegate() != null && ask.delegate().systemPrompt() != null
          ? ask.delegate().systemPrompt() : cfg.systemPrompt();
      if (base != null && !base.isBlank()) parts.add(base.strip());
      List<Chat.Section> sections;
      try {
        sections = new ArrayList<>(ctx.collect(Chat.CONTEXT, new Chat.ContextQuery(ask.conversationId(),
            ask.delegate() == null ? null : ask.delegate().origin(),
            ask.delegate() == null ? null : ask.delegate().agent())));
      } catch (EmitException e) {
        sections = new ArrayList<>(); // a contributor that did not go through Chat.contribute failed
      }
      sections.sort(Comparator.comparingInt(Chat.Section::order).thenComparing(Chat.Section::name));
      for (Chat.Section s : sections) {
        if (!s.text().isBlank()) parts.add("## " + s.name() + "\n" + s.text().strip());
      }
      return String.join("\n\n", parts);
    }

    /** The system message: prompt, sections and summary; null when all are empty. */
    private Message system() {
      String text = prompt;
      if (summary != null) {
        String part = "## " + SUMMARY_HEADING + "\n" + summary;
        text = text.isEmpty() ? part : text + "\n\n" + part;
      }
      return text.isEmpty() ? null : Message.system(text);
    }

    /** Synchronized: calls running in parallel add their own usage. */
    private synchronized void count(Usage u) {
      usage = new Usage(usage.promptTokens() + u.promptTokens(), usage.completionTokens() + u.completionTokens());
    }

    /** A tool call of the model's reply, as planned before any runs: refused ({@code refusal}) or to run. */
    private record Planned(ToolCall call, ToolResult refusal) {
    }

    /**
     * Runs the calls of one model reply and adds their results in call order; returns how the turn goes on
     * ({@link Chat#DONE}) or why it stops. Every call gets a result, even when the turn stops here, so the
     * history stays valid: once the turn is stopped (a limit reached before the calls) the remaining calls get a
     * "not run" result. A call repeated more than {@code maxRepeats} times in a row is refused, as feedback for the
     * model; it does not stop the turn.
     *
     * Repeats are counted in call order first. Then the calls run one after the other, or up to
     * {@code parallelTools} at once on tasks of this plugin, so disposal cancels and awaits them like the
     * turn itself. Their steps are reported one at a time, in the order they start and end.
     */
    private String runCalls(List<ToolCall> calls) {
      String next = stop;
      List<Planned> planned = new ArrayList<>();
      List<ToolCall> notRun = new ArrayList<>();
      for (ToolCall call : calls) {
        if (!Chat.DONE.equals(next)) {
          notRun.add(call);
          continue;
        }
        String key = call.name() + " " + canonical(call.arguments());
        repeats = key.equals(lastCall) ? repeats + 1 : 1;
        lastCall = key;
        if (ask.delegate() != null && !ask.delegate().allows(call.name())) {
          planned.add(new Planned(call, ToolResult.error(call.name() + " is not available to this agent")));
        } else if (repeats > cfg.maxRepeats()) {
          // feedback, not a stop: only the hard limits end a turn
          planned.add(new Planned(call, ToolResult.error("the same call was made " + repeats + " times in a row; "
              + "change the arguments or answer with what you have")));
        } else {
          toolCalls++;
          ran.merge(call.name(), 1, Integer::sum);
          planned.add(new Planned(call, null));
        }
      }

      ToolResult[] results = new ToolResult[planned.size()];
      long running = planned.stream().filter(p -> p.refusal() == null).count();
      if (cfg.parallelTools() <= 1 || running <= 1) {
        for (int i = 0; i < planned.size(); i++) results[i] = step(planned.get(i));
      } else {
        runParallel(planned, results, (int) Math.min(cfg.parallelTools(), running));
      }
      for (int i = 0; i < planned.size(); i++) {
        ToolResult r = results[i];
        add(Message.tool(planned.get(i).call().id(), r.isError() ? Chat.TOOL_ERROR_PREFIX + r.content() : r.content()));
      }
      for (ToolCall call : notRun) {
        add(Message.tool(call.id(), Chat.TOOL_ERROR_PREFIX + "not run, the turn was stopped (" + next + ")"));
      }
      return next;
    }

    /**
     * Runs {@code planned} on {@code workers} tasks that take the next call until none is left. Waits for all of
     * them; the first failure (a cancelled turn) is thrown once they are done, and an interrupted wait cancels
     * them before it returns.
     */
    private void runParallel(List<Planned> planned, ToolResult[] results, int workers) {
      AtomicInteger nextCall = new AtomicInteger();
      List<Task<Void>> tasks = new ArrayList<>();
      try {
        for (int w = 0; w < workers; w++) {
          tasks.add(ctx.spawn("tool-call", t -> {
            int i;
            while ((i = nextCall.getAndIncrement()) < planned.size()) results[i] = step(planned.get(i));
            return null;
          }));
        }
        RuntimeException failure = null;
        for (Task<Void> task : tasks) {
          try {
            task.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            signal.cancel();
            throw new CancelledException("interrupted while tools were running");
          } catch (RuntimeException e) {
            if (failure == null) failure = e;
          }
        }
        if (failure != null) throw failure;
      } finally {
        for (Task<Void> task : tasks) task.dispose(); // cancels and awaits what is still running
      }
    }

    /** Runs one planned call (or reports its refusal) as a {@code tool} span; returns its result for the model. */
    private ToolResult step(Planned planned) {
      ToolCall call = planned.call();
      report(new Chat.Step(ask.conversationId(), call, null));
      OpenSpan s = span.child("tool").attr("tool", call.name()).attr("callId", call.id());
      ToolResult result;
      try {
        if (planned.refusal() != null) {
          result = planned.refusal();
          s.attr("refused", true);
        } else {
          ToolResult raw = runTool(call, s);
          count(raw.usage());
          if (raw.usage().totalTokens() > 0) {
            s.attr("promptTokens", raw.usage().promptTokens()).attr("completionTokens", raw.usage().completionTokens());
          }
          result = truncate(raw);
          s.attr("outputChars", raw.content().length()).attr("truncated", result != raw);
        }
      } catch (RuntimeException e) {
        fail(s, e);
        throw e;
      }
      if (result.isError()) s.fail(firstLine(result.content()));
      else s.end();
      report(new Chat.Step(ask.conversationId(), call, result));
      return result;
    }

    private static String firstLine(String text) {
      int nl = text.indexOf('\n');
      return cut(nl < 0 ? text : text.substring(0, nl));
    }

    /**
     * Runs the call with its own timeout; a timeout is an error for the model, a cancelled ask is thrown. The
     * clock starts when the tool starts, so the time a user takes to approve the call does not count; that time
     * is kept on the tool's span as {@code approvalMicros}.
     */
    private ToolResult runTool(ToolCall call, OpenSpan s) {
      int own = Tools.info(ctx, call.name()).map(Tools.Info::timeoutSeconds).orElse(0);
      int limit = own > 0 ? own : cfg.toolTimeoutSeconds();
      CancelToken token = new CancelToken();
      Disposable link = signal.onCancel(token::cancel);
      AtomicBoolean timedOut = new AtomicBoolean();
      AtomicReference<Task<Void>> timer = new AtomicReference<>();
      Runnable started = () -> {
        s.attr("approvalMicros", s.elapsedMicros());
        timer.set(ctx.spawn("tool-timeout", t -> {
          try {
            Thread.sleep(limit * 1000L);
          } catch (InterruptedException e) {
            return null; // the call finished first
          }
          timedOut.set(true);
          token.cancel();
          return null;
        }));
      };
      try {
        Chat.Delegate d = ask.delegate();
        ToolContext tc = new ToolContext(ask.conversationId(), token, started, d == null ? null : d.origin(),
            d == null ? null : d.agent(), span.traceId(), s.id(), p -> progress(call, p));
        return Tools.call(ctx, call, tc);
      } catch (CancelledException e) {
        if (timedOut.get() && !signal.isCancelled()) {
          s.attr("timedOut", true);
          return ToolResult.error(call.name() + " timed out after " + limit + "s");
        }
        throw e;
      } finally {
        Task<Void> t = timer.get();
        if (t != null) t.dispose();
        link.dispose();
      }
    }

    private ToolResult truncate(ToolResult result) {
      String content = result.content();
      if (content.length() <= cfg.maxToolOutput()) return result;
      int cut = content.length() - cfg.maxToolOutput();
      return result.withContent(content.substring(0, cfg.maxToolOutput()) + "\n… [truncated " + cut + " chars]");
    }

    /**
     * What tool {@code call} shows while it runs (a sub-agent's text and tool calls), passed on to the asker
     * only: the sub-agent's own turn already emits its steps on {@link Chat#STEP}. Deeper sub-agents are reported
     * under the same call.
     */
    private void progress(ToolCall call, panic.tools.ToolProgress p) {
      synchronized (callbacks) {
        if (p.isText()) {
          ask.onAgentDelta().accept(new Chat.AgentDelta(call.id(), p.agent(), p.text()));
        } else {
          ask.onStep().accept(new Chat.Step(ask.conversationId(), p.call(), p.result(), call.id(), p.agent()));
        }
      }
    }

    /** One at a time, also when calls run in parallel: an asker's callbacks need not be thread-safe. */
    private void report(Chat.Step step) {
      synchronized (callbacks) {
        ask.onStep().accept(step);
        ctx.emit(Chat.STEP, step);
      }
    }

    private void add(Message m) {
      added.add(m);
    }

    /**
     * A question {@code /name args} that a plugin answers on {@link Prompts#EXPAND} (a skill): the user's line
     * stays as written, and the prompt follows it. With a tool it is recorded as that tool's call and result, so
     * the history has the shape it has when the model loads the skill itself, compaction treats it as tool output
     * (not as the user's words to keep), and front-ends show it as a step. Nobody answers: the line goes as is.
     */
    private void expand() {
      Optional<Prompts.Line> line = Prompts.parse(ask.text());
      if (line.isEmpty()) return;
      Optional<Prompts.Prompt> found = Prompts.expand(ctx, ask.conversationId(), line.get().name(), line.get().args());
      if (found.isEmpty()) return;
      Prompts.Prompt p = found.get();
      span.attr("prompt", p.name());
      if (p.tool() == null) {
        add(Message.user(p.text()));
        return;
      }
      ToolCall call = new ToolCall("pre_" + java.util.UUID.randomUUID().toString().substring(0, 8), p.tool(),
          p.toolArguments());
      ToolResult result = truncate(ToolResult.ok(p.text()));
      report(new Chat.Step(ask.conversationId(), call, null));
      add(Message.assistant("", List.of(call)));
      add(Message.tool(call.id(), result.content()));
      report(new Chat.Step(ask.conversationId(), call, result));
    }

    private static String canonical(String arguments) {
      JsonNode args = Json.parseOrNull(arguments);
      if (args == null) return arguments;
      try {
        return CANONICAL.writeValueAsString(CANONICAL.treeToValue(args, Object.class));
      } catch (JsonProcessingException e) {
        return arguments;
      }
    }
  }

  /** Prints a streamed answer and its tool calls on their own lines. */
  private static final class Printer {

    private final Out out;
    private boolean midLine;

    Printer(Out out) {
      this.out = out;
    }

    void text(String delta) {
      out.print(delta);
      midLine = !delta.endsWith("\n");
    }

    /** A sub-agent's calls are indented under the call that runs it and carry its name. */
    void step(Chat.Step step) {
      endLine();
      ToolCall call = step.call();
      String tag = step.nested() ? "  [" + step.agent() + ": tool " : "[tool ";
      if (!step.done()) {
        out.println(tag + call.name() + " " + call.arguments() + "]");
      } else if (step.result().isError()) {
        out.println(tag + call.name() + ": error: " + firstLine(step.result().content()) + "]");
      }
    }

    /** Rejections only: an accepted answer needs no note. */
    void review(Chat.Reviewed r) {
      if (r.failed()) {
        endLine();
        out.println("[review failed: " + r.error() + "]");
        return;
      }
      if (r.verdict().pass()) return;
      endLine();
      out.println("[review: " + r.verdict().reason() + "]");
    }

    void endLine() {
      if (midLine) out.println();
      midLine = false;
    }

    private static String firstLine(String text) {
      int nl = text.indexOf('\n');
      String line = nl < 0 ? text : text.substring(0, nl) + " …";
      return line.length() > 200 ? line.substring(0, 200) + " …" : line;
    }
  }
}
