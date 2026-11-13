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
import java.util.concurrent.atomic.AtomicReference;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.Out;
import panic.llm.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Json;
import panic.llm.Llm;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

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
 * model as a user message, at most {@link ChatConfig#maxReviews} times; a reviewer that fails accepts.
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

  /** Tells the model how to summarise the turns that no longer fit. */
  static final String SUMMARY_PROMPT = "You keep the memory of a conversation between a user and an assistant "
      + "that uses tools. Write a summary of the conversation below for the assistant to continue from. Keep "
      + "the user's goals and requests, decisions made, facts learned (names, paths, values, results of tool "
      + "calls) and what is still open. Leave out greetings and anything not needed later. Do not invent "
      + "anything. Answer with the summary only.";

  /** Heading of the summary in the system prompt. */
  static final String SUMMARY_HEADING = "Summary of the earlier conversation";

  /** Characters of one message shown to the summariser; tool output beyond this is rarely worth keeping. */
  private static final int SUMMARY_MESSAGE_CHARS = 2_000;

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
    private String lastCall;
    private int repeats;
    private int reviews;
    private String review;

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
      try {
        return loop();
      } finally {
        byPlugin.dispose();
        byAsker.dispose();
      }
    }

    private Chat.Reply loop() {
      Conversation conversation = conversations.get(ask.conversationId());
      prompt = prompt();
      Conversation.Snapshot snapshot = conversation.snapshot();
      summary = snapshot.summary();
      history = snapshot.messages();
      add(Message.user(ask.text()));
      if (cfg.limitsContext() && cfg.compact()) compact(conversation);

      Completion last;
      String stop = Chat.DONE;
      int steps = 0;
      while (true) {
        steps++;
        ChatRequest request = request();
        last = llm.stream(request, signal, ask.onDelta(), ask.onToolDelta());
        tokens.observe(Tokens.raw(request), last.usage().promptTokens());
        count(last.usage());
        add(last.message());
        if (!last.hasToolCalls()) {
          Chat.Verdict verdict = review(last.text());
          if (verdict == null || verdict.pass()) break;
          if (reviews >= cfg.maxReviews() || steps >= cfg.maxSteps()) {
            stop = Chat.REJECTED;
            break;
          }
          reviews++;
          add(Message.user(Chat.REVIEW_FEEDBACK_PREFIX + verdict.reason()
              + "\nFix what is missing (use tools if needed), then give your final answer again."));
          continue;
        }

        if (steps >= cfg.maxSteps()) {
          stop = Chat.MAX_STEPS;
        } else if (cfg.maxTokens() > 0 && usage.totalTokens() >= cfg.maxTokens()) {
          stop = Chat.BUDGET;
        }
        // every call gets a result, even when the turn stops here, so the history stays valid
        for (ToolCall call : last.toolCalls()) {
          if (!Chat.DONE.equals(stop)) {
            add(Message.tool(call.id(), Chat.TOOL_ERROR_PREFIX + "not run, the turn was stopped (" + stop + ")"));
          } else {
            stop = step(call);
          }
        }
        if (!Chat.DONE.equals(stop)) break;
      }

      signal.throwIfCancelled();
      conversation.append(added.toArray(Message[]::new));
      Chat.Reply reply = new Chat.Reply(last.text(), last.model(), last.finishReason(), usage, stop, steps,
          toolCalls, review);
      ctx.emit(Chat.TURN, new Chat.Turn(ask.conversationId(), ask.text(), reply));
      return reply;
    }

    /**
     * Puts the final {@code answer} to {@link Chat#REVIEW}. Null when reviews are off or nobody judged it; a
     * reviewer that fails counts as no verdict, so a broken reviewer never blocks an answer. Cancellation is thrown.
     */
    private Chat.Verdict review(String answer) {
      if (cfg.maxReviews() == 0) return null;
      int attempt = reviews + 1;
      Optional<Chat.Verdict> verdict;
      try {
        verdict = Hooks.bail(ctx, Chat.REVIEW,
            new Chat.Review(ask.conversationId(), ask.text(), added, answer, attempt, signal));
      } catch (RuntimeException e) {
        signal.throwIfCancelled();
        if (e instanceof CancelledException c) throw c;
        return null;
      }
      signal.throwIfCancelled();
      if (verdict.isEmpty()) return null;
      count(verdict.get().usage());
      review = verdict.get().reason();
      Chat.Reviewed reviewed = new Chat.Reviewed(ask.conversationId(), attempt, verdict.get());
      ask.onReview().accept(reviewed);
      ctx.emit(Chat.REVIEWED, reviewed);
      return verdict.get();
    }

    /**
     * The request for the next model call: system message, the earlier turns that fit, then the whole current
     * turn. Built again for every call, as the turn grows and tools come and go.
     */
    private ChatRequest request() {
      List<ToolSpec> specs = cfg.tools() ? Tools.specs(ctx) : List.of();
      Message system = system();
      List<Message> messages = new ArrayList<>();
      if (system != null) messages.add(system);
      messages.addAll(fit(system, specs, 1, cfg.maxHistory()).kept());
      messages.addAll(added);
      ChatRequest request = ChatRequest.of(messages);
      return specs.isEmpty() ? request : request.withTools(specs, null);
    }

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
      List<ToolSpec> specs = cfg.tools() ? Tools.specs(ctx) : List.of();
      if (fit(system(), specs, 1, 0).dropped().isEmpty()) return;
      ContextWindow.Fit target = fit(system(), specs, 0.5, 0);
      String next = summarize(target.dropped());
      if (next != null && conversation.compact(target.dropped(), next)) {
        summary = next;
        history = target.kept();
      }
    }

    private String summarize(List<Message> turns) {
      int maxTokens = Math.max(256, Math.min(2_048, cfg.contextTokens() / 8));
      ChatRequest request = new ChatRequest(
          List.of(Message.system(SUMMARY_PROMPT), Message.user(transcript(turns))), null, null, maxTokens);
      try {
        Completion c = llm.complete(request, signal);
        count(c.usage());
        String text = c.text().strip();
        return text.isEmpty() ? null : text;
      } catch (LlmException e) {
        signal.throwIfCancelled();
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

    private static String clip(String text) {
      if (text.length() <= SUMMARY_MESSAGE_CHARS) return text;
      return text.substring(0, SUMMARY_MESSAGE_CHARS) + " … [" + (text.length() - SUMMARY_MESSAGE_CHARS)
          + " chars cut]";
    }

    /** The configured prompt followed by the sections contributed through {@link Chat#CONTEXT}. */
    private String prompt() {
      List<String> parts = new ArrayList<>();
      if (cfg.systemPrompt() != null && !cfg.systemPrompt().isBlank()) parts.add(cfg.systemPrompt().strip());
      List<Chat.Section> sections;
      try {
        sections = new ArrayList<>(ctx.collect(Chat.CONTEXT, new Chat.ContextQuery(ask.conversationId())));
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

    private void count(Usage u) {
      usage = new Usage(usage.promptTokens() + u.promptTokens(), usage.completionTokens() + u.completionTokens());
    }

    /** Runs one tool call and records its result. Returns {@link Chat#LOOP} when the model is going round in circles. */
    private String step(ToolCall call) {
      String key = call.name() + " " + canonical(call.arguments());
      repeats = key.equals(lastCall) ? repeats + 1 : 1;
      lastCall = key;

      report(new Chat.Step(ask.conversationId(), call, null));
      ToolResult result;
      String stop = Chat.DONE;
      if (repeats > cfg.maxRepeats()) {
        result = ToolResult.error("the same call was made " + repeats + " times in a row; change the arguments "
            + "or answer with what you have");
        if (repeats > cfg.maxRepeats() + 1) stop = Chat.LOOP;
      } else {
        toolCalls++;
        result = truncate(runTool(call));
      }
      add(Message.tool(call.id(), result.isError() ? Chat.TOOL_ERROR_PREFIX + result.content() : result.content()));
      report(new Chat.Step(ask.conversationId(), call, result));
      return stop;
    }

    /**
     * Runs the call with its own timeout; a timeout is an error for the model, a cancelled ask is thrown. The
     * clock starts when the tool starts, so the time a user takes to approve the call does not count.
     */
    private ToolResult runTool(ToolCall call) {
      CancelToken token = new CancelToken();
      Disposable link = signal.onCancel(token::cancel);
      AtomicBoolean timedOut = new AtomicBoolean();
      AtomicReference<Task<Void>> timer = new AtomicReference<>();
      Runnable started = () -> timer.set(ctx.spawn("tool-timeout", s -> {
        try {
          Thread.sleep(cfg.toolTimeoutSeconds() * 1000L);
        } catch (InterruptedException e) {
          return null; // the call finished first
        }
        timedOut.set(true);
        token.cancel();
        return null;
      }));
      try {
        return Tools.call(ctx, call, new ToolContext(ask.conversationId(), token, started));
      } catch (CancelledException e) {
        if (timedOut.get() && !signal.isCancelled()) {
          return ToolResult.error(call.name() + " timed out after " + cfg.toolTimeoutSeconds() + "s");
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
      return new ToolResult(content.substring(0, cfg.maxToolOutput()) + "\n… [truncated " + cut + " chars]",
          result.isError());
    }

    private void report(Chat.Step step) {
      ask.onStep().accept(step);
      ctx.emit(Chat.STEP, step);
    }

    private void add(Message m) {
      added.add(m);
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

    void step(Chat.Step step) {
      endLine();
      ToolCall call = step.call();
      if (!step.done()) {
        out.println("[tool " + call.name() + " " + call.arguments() + "]");
      } else if (step.result().isError()) {
        out.println("[tool " + call.name() + ": error: " + firstLine(step.result().content()) + "]");
      }
    }

    /** Rejections only: an accepted answer needs no note. */
    void review(Chat.Reviewed r) {
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
