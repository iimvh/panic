package panic.interact;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import context.Task;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import panic.chat.Chat;
import panic.command.Hooks;
import panic.llm.ToolSpec;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;
import panic.util.Json;

/**
 * Lets the agent ask the user in the middle of a turn, as Claude Code or Cursor do: tool {@value AskUser#TOOL}
 * puts one to a few multiple-choice questions to {@link AskUser#ASK} and waits for the answers, which go back to
 * the model as the tool's result, so the turn goes on with them. Risk {@code read}: it changes nothing, is allowed
 * in plan mode (asking before planning is the point) and is never itself put to the permission policy.
 *
 * Only the main agent may ask: a sub-agent's call is refused, so no sub-agent blocks on the user. No answer, a
 * timeout ({@link AskUserConfig#timeoutSeconds}) or a skip tells the model not to ask again, and to go on with
 * stated assumptions or end its answer asking in plain words.
 * Requires nothing; cancelling the turn or disposing the plugin cancels a wait in progress.
 */
public final class AskUserPlugin implements ConfigPlugin<AskUserConfig> {

  static final String SECTION = "Asking the user";

  static final String HINT = "When a decision is genuinely the user's (a preference, a trade-off, an unclear "
      + "requirement), ask with " + AskUser.TOOL + " and a few concrete options instead of guessing. Do not ask "
      + "what you can find out with your tools, and do not ask for permission to use them.";

  @Override
  public Disposable apply(Context ctx, AskUserConfig cfg) {
    Tools.register(ctx, new Ask(ctx, cfg));
    Chat.contribute(ctx, SECTION, 6, q -> HINT);
    return null;
  }

  private record Ask(Context ctx, AskUserConfig cfg) implements Tool {

    @Override
    public ToolSpec spec() {
      return new ToolSpec(AskUser.TOOL, "Ask the user one to " + cfg.maxQuestions() + " multiple-choice questions "
          + "and wait for the answers (the user can also type their own). Use it for decisions only the user can "
          + "make; each question has 2 to " + cfg.maxOptions() + " short options.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "questions":{"type":"array","items":{"type":"object","properties":{
                  "question":{"type":"string","description":"the question, ending with a question mark"},
                  "header":{"type":"string","description":"a very short label (1-3 words)"},
                  "options":{"type":"array","items":{"type":"object","properties":{
                    "label":{"type":"string","description":"the choice, 1-5 words"},
                    "description":{"type":"string","description":"what it means or implies"}},
                   "required":["label"],"additionalProperties":false}},
                  "multiSelect":{"type":"boolean","description":"several options may be picked"}},
                 "required":["question","options"],"additionalProperties":false}}},
               "required":["questions"],"additionalProperties":false}"""));
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    /** The wait for the user is the tool's run, so chat's tool timeout must not cut it. */
    @Override
    public int timeoutSeconds() {
      return cfg.timeoutSeconds() + 10;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      if (tc.agent() != null) {
        return ToolResult.error("sub-agents cannot ask the user: report what you need to decide in your answer");
      }
      List<AskUser.Question> questions;
      try {
        questions = questions(args.get("questions"));
      } catch (IllegalArgumentException e) {
        return ToolResult.error("invalid questions: " + e.getMessage());
      }
      Optional<AskUser.Answers> answers = ask(tc.signal(), tc.origin(), questions);
      if (answers.isEmpty() || answers.get().skipped()) {
        String why = answers.isEmpty() ? "no answer" : "skipped";
        return ToolResult.error("the user did not answer (" + why + "). Do not ask the same again: go on with "
            + "stated assumptions where they are safe, or end your answer and ask in plain words.");
      }
      return ToolResult.ok(render(questions, answers.get()));
    }

    private List<AskUser.Question> questions(JsonNode array) {
      if (array.isEmpty() || array.size() > cfg.maxQuestions()) {
        throw new IllegalArgumentException("ask 1 to " + cfg.maxQuestions() + " questions, not " + array.size());
      }
      List<AskUser.Question> out = new ArrayList<>();
      for (int i = 0; i < array.size(); i++) {
        JsonNode q = array.get(i);
        String text = q.get("question").asText().strip();
        if (text.isEmpty()) throw new IllegalArgumentException("questions[" + i + "].question is empty");
        JsonNode opts = q.get("options");
        if (opts.size() < 2 || opts.size() > cfg.maxOptions()) {
          throw new IllegalArgumentException("questions[" + i + "] needs 2 to " + cfg.maxOptions() + " options, not "
              + opts.size());
        }
        List<AskUser.Option> options = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (JsonNode o : opts) {
          String label = o.get("label").asText().strip();
          if (label.isEmpty()) throw new IllegalArgumentException("questions[" + i + "] has an option without a label");
          if (!labels.add(label)) throw new IllegalArgumentException("questions[" + i + "] repeats option '" + label + "'");
          options.add(new AskUser.Option(label, o.path("description").asText("")));
        }
        out.add(new AskUser.Question(text, q.path("header").asText(""), options, q.path("multiSelect").asBoolean(false)));
      }
      return out;
    }

    /** One line per question: {@code "question" → picked, picked (other: text)}. */
    static String render(List<AskUser.Question> questions, AskUser.Answers answers) {
      StringBuilder out = new StringBuilder("The user answered:");
      for (int i = 0; i < questions.size(); i++) {
        AskUser.Answer a = i < answers.answers().size() ? answers.answers().get(i) : new AskUser.Answer(null, null);
        out.append("\n\"").append(questions.get(i).question()).append("\" → ");
        if (a.isEmpty()) {
          out.append("(no answer)");
          continue;
        }
        out.append(String.join(", ", a.selected()));
        if (!a.other().isEmpty()) out.append(a.selected().isEmpty() ? "" : " ").append("(other: ").append(a.other()).append(')');
      }
      return out.toString();
    }

    /**
     * Asks {@link AskUser#ASK} and waits at most {@code timeoutSeconds}; empty when nobody answered in time. A
     * cancelled turn (or the plugin's disposal) is thrown.
     */
    private Optional<AskUser.Answers> ask(Signal caller, String conversationId, List<AskUser.Question> questions) {
      CancelToken wait = new CancelToken();
      Disposable byCaller = caller.onCancel(wait::cancel);
      Disposable byOwner = ctx.signal().onCancel(wait::cancel);
      AtomicBoolean timedOut = new AtomicBoolean();
      int seconds = cfg.timeoutSeconds();
      Task<Void> timer = ctx.spawn("ask-user-timeout", s -> {
        try {
          Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
          return null; // answered first
        }
        timedOut.set(true);
        wait.cancel();
        return null;
      });
      try {
        String id = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        Optional<AskUser.Answers> a = Hooks.bail(ctx, AskUser.ASK, new AskUser.Request(id, conversationId, questions, wait));
        if (caller.isCancelled() || ctx.signal().isCancelled()) throw new CancelledException("question cancelled");
        return timedOut.get() ? Optional.empty() : a;
      } catch (CancelledException e) {
        if (timedOut.get() && !caller.isCancelled() && !ctx.signal().isCancelled()) return Optional.empty();
        throw e;
      } finally {
        timer.dispose();
        byOwner.dispose();
        byCaller.dispose();
      }
    }
  }
}
