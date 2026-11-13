package panic.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import context.ConfigPlugin;
import context.Context;
import context.ServiceKey;
import context.Task;
import core.CancelledException;
import core.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import panic.command.Commands;
import panic.command.Out;
import panic.llm.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Json;
import panic.llm.Llm;
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
    ctx.hook(Chat.ASK, ask -> new Turn(ctx, cfg, llm, conversations, ask).run());
    Commands.command(ctx, "ask <text...>", "ask the bot (plain text does the same)", call -> {
      Printer printer = new Printer(call.out());
      Chat.Ask ask = new Chat.Ask(call.session().id(), call.arg("text"), call.signal(), printer::text,
          printer::step);
      Chat.Reply r = new Turn(ctx, cfg, llm, conversations, ask).run();
      printer.endLine();
      StringBuilder meta = new StringBuilder("[").append(r.model());
      if (r.usage().totalTokens() > 0) meta.append(" · ").append(r.usage().totalTokens()).append(" tokens");
      if (r.toolCalls() > 0) meta.append(" · ").append(r.toolCalls()).append(r.toolCalls() == 1 ? " tool" : " tools");
      if (!Chat.DONE.equals(r.stop())) {
        meta.append(" · stopped: ").append(r.stop());
      } else if (r.finishReason() != null && !"stop".equals(r.finishReason())) {
        meta.append(" · ").append(r.finishReason());
      }
      call.out().println(meta.append("]").toString());
    });
    return null;
  }

  /** One question, from the user's message to the bot's answer. */
  private static final class Turn {

    private final Context ctx;
    private final ChatConfig cfg;
    private final Llm llm;
    private final Conversations conversations;
    private final Chat.Ask ask;
    private final CancelToken signal;

    private final List<Message> sent = new ArrayList<>();
    private final List<Message> added = new ArrayList<>();
    private Usage usage = Usage.NONE;
    private int toolCalls;
    private String lastCall;
    private int repeats;

    Turn(Context ctx, ChatConfig cfg, Llm llm, Conversations conversations, Chat.Ask ask) {
      this.ctx = ctx;
      this.cfg = cfg;
      this.llm = llm;
      this.conversations = conversations;
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
      if (cfg.systemPrompt() != null && !cfg.systemPrompt().isBlank()) sent.add(Message.system(cfg.systemPrompt()));
      sent.addAll(conversation.recent(cfg.maxHistory()));
      add(Message.user(ask.text()));

      Completion last;
      String stop = Chat.DONE;
      int steps = 0;
      while (true) {
        steps++;
        last = llm.stream(request(), signal, ask.onDelta());
        usage = new Usage(usage.promptTokens() + last.usage().promptTokens(),
            usage.completionTokens() + last.usage().completionTokens());
        add(last.message());
        if (!last.hasToolCalls()) break;

        if (steps >= cfg.maxSteps()) {
          stop = Chat.MAX_STEPS;
        } else if (cfg.maxTokens() > 0 && usage.totalTokens() >= cfg.maxTokens()) {
          stop = Chat.BUDGET;
        }
        // every call gets a result, even when the turn stops here, so the history stays valid
        for (ToolCall call : last.toolCalls()) {
          if (!Chat.DONE.equals(stop)) {
            add(Message.tool(call.id(), "error: not run, the turn was stopped (" + stop + ")"));
          } else {
            stop = step(call);
          }
        }
        if (!Chat.DONE.equals(stop)) break;
      }

      signal.throwIfCancelled();
      conversation.append(added.toArray(Message[]::new));
      Chat.Reply reply = new Chat.Reply(last.text(), last.model(), last.finishReason(), usage, stop, steps,
          toolCalls);
      ctx.emit(Chat.TURN, new Chat.Turn(ask.conversationId(), ask.text(), reply));
      return reply;
    }

    private ChatRequest request() {
      ChatRequest request = ChatRequest.of(sent);
      if (!cfg.tools()) return request;
      List<ToolSpec> specs = Tools.specs(ctx);
      return specs.isEmpty() ? request : request.withTools(specs, null);
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
      add(Message.tool(call.id(), result.isError() ? "error: " + result.content() : result.content()));
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
      sent.add(m);
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
