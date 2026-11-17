package panic.planner;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import context.Task;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import panic.chat.Chat;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.UsageException;
import panic.llm.ToolSpec;
import panic.todo.Todo;
import panic.todo.TodoItem;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;
import panic.util.Json;

/**
 * Plan mode, as in Claude Code or Cursor: while a conversation is in plan mode the agent may only read (tools of
 * risk {@code read}, plus {@link PlannerConfig#allow}); writes, commands and network calls are refused by a
 * {@link Tools#GATE}, even in bypass mode. They stay offered, so a call tells the model why and to propose its plan,
 * instead of the model hunting for another way to act. A section of
 * the system prompt tells it to research and then propose a plan with tool {@value Planner#EXIT_TOOL}. That tool
 * asks the user through {@link Planner#APPROVE}; approved, the mode ends, the plan becomes the conversation's task
 * list and criteria ({@link Todo#WRITE}, when {@code todo} runs) and the agent goes on in the same turn with all
 * tools. Not approved, the feedback goes back to the model and the mode stays.
 *
 * Every proposal is also written to a markdown file in the conversation's project ({@link PlanFiles}), with its
 * status; once approved, its text is in the system prompt ({@code Approved plan}) while the agent carries it out,
 * and command {@code plan show} prints it.
 *
 * Requires nothing and nothing requires it. The per-conversation switches (command {@code plan}) live in memory
 * only, like bypass mode, and are forgotten when the plugin restarts (a config change). A proposal waiting for
 * the user is cancelled with the turn or the plugin's disposal.
 */
public final class PlannerPlugin implements ConfigPlugin<PlannerConfig> {

  static final String SECTION = "Plan mode";

  static final String INSTRUCTIONS = "Plan mode is on: the user wants a plan before any change. Research with the "
      + "read-only tools you have (read files, list folders, ask read-only sub-agents) and do not try to change "
      + "anything: tools that write, run commands or use the network are not available until the plan is approved. "
      + "When you understand the task, call " + Planner.EXIT_TOOL + " with the plan in markdown (what you found, "
      + "the approach, the files to change), the ordered steps as `items` and checkable acceptance `criteria`. "
      + "Once approved, the steps become your task list by themselves. If the user sends the plan back with "
      + "feedback, revise it and propose it again; if they give no reason, stop and ask them in your answer. For a "
      + "question that needs no change, just answer it. Sub-agents are read-only too: do not hand them work that "
      + "changes anything, and never report a step as done before the plan is approved and the step carried out. "
      + "Shell commands (npm, git, ...) run only after the plan is approved: list the ones you will run as steps.";

  /** What a refused call returns in plan mode, to the main agent and to its sub-agents. */
  static final String BLOCKED = "plan mode is on: nothing can be changed until the user approves a plan. Call "
      + Planner.EXIT_TOOL + " with your plan now (or just answer, if no change is needed).";

  static final String APPROVED_SECTION = "Approved plan";

  /** The approved plan's text in the system prompt is cut here; the file keeps all of it. */
  static final int MAX_PLAN_CHARS = 8_000;

  @Override
  public Disposable apply(Context ctx, PlannerConfig cfg) {
    // only conversations that differ from the config default are kept
    Map<String, Boolean> overrides = new ConcurrentHashMap<>();
    ctx.hook(Planner.MODE, q -> new Planner.Mode(on(cfg, overrides, q.conversationId()), cfg.byDefault()));
    ctx.hook(Tools.GATE, g -> {
      boolean planning = g.conversationId() != null && on(cfg, overrides, g.conversationId());
      if (g.name().equals(Planner.EXIT_TOOL)) return planning ? null : new Tools.Refusal("only in plan mode");
      if (!planning || g.risk() == Risk.READ || cfg.allow().contains(g.name())) return null;
      // offered but refused: the model learns why it cannot act instead of looking for another way
      return new Tools.Refusal(BLOCKED, false);
    });
    PlanFiles files = new PlanFiles(ctx, cfg);
    Chat.contribute(ctx, SECTION, 5, q -> on(cfg, overrides, q.conversationId()) ? INSTRUCTIONS : null);
    Chat.contribute(ctx, APPROVED_SECTION, 45, q -> {
      if (on(cfg, overrides, q.conversationId())) return null; // a new plan is being made
      return files.read(q.conversationId()).filter(PlanFiles.Saved::approved)
          .map(p -> "The user approved this plan (saved in " + p.path() + "); carry it out:\n\n" + clip(p.body()))
          .orElse(null);
    });
    Tools.register(ctx, new Exit(ctx, cfg, overrides, files));
    Commands.command(ctx, "plan [state]",
        "plan mode in this chat: on (research read-only, then propose a plan to approve) | off | status | show",
        call -> {
          String id = call.session().id();
          String state = call.argOr("state", "status");
          switch (state) {
            case "on" -> {
              set(ctx, cfg, overrides, id, true);
              call.out().println("plan on: the agent researches read-only and proposes a plan for you to approve "
                  + "before it changes anything");
            }
            case "off" -> {
              set(ctx, cfg, overrides, id, false);
              call.out().println("plan off: the agent can use all its tools again");
            }
            case "status" -> call.out().println("plan " + (on(cfg, overrides, id) ? "on" : "off")
                + " in this chat (config default: " + (cfg.byDefault() ? "on" : "off") + ")"
                + files.find(id).map(f -> "; plan file: " + f).orElse(""));
            case "show" -> call.out().println(files.read(id)
                .map(p -> p.path() + " (" + p.status() + ")\n\n" + p.body())
                .orElse("no plan saved for this chat (it would go in " + files.dir(id) + ")"));
            default -> throw new UsageException("unknown state '" + state + "'", "plan [on|off|status|show]");
          }
        });
    return null;
  }

  private static String clip(String text) {
    return text.length() <= MAX_PLAN_CHARS ? text
        : text.substring(0, MAX_PLAN_CHARS) + "\n… [" + (text.length() - MAX_PLAN_CHARS) + " chars more in the file]";
  }

  private static boolean on(PlannerConfig cfg, Map<String, Boolean> overrides, String conversationId) {
    return overrides.getOrDefault(conversationId, cfg.byDefault());
  }

  private static void set(Context ctx, PlannerConfig cfg, Map<String, Boolean> overrides, String id, boolean on) {
    boolean was = on(cfg, overrides, id);
    if (on == cfg.byDefault()) overrides.remove(id);
    else overrides.put(id, on);
    if (was != on) ctx.emit(Planner.CHANGED, new Planner.ModeChanged(id, on));
  }

  /** {@value Planner#EXIT_TOOL}: proposes the plan, waits for the user, and leaves plan mode when approved. */
  private record Exit(Context ctx, PlannerConfig cfg, Map<String, Boolean> overrides, PlanFiles files)
      implements Tool {

    @Override
    public ToolSpec spec() {
      return new ToolSpec(Planner.EXIT_TOOL, "Propose your plan to the user and, once they approve it, leave plan "
          + "mode and carry it out with all your tools. Only in plan mode, after you have researched the task.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "plan":{"type":"string","description":"the plan in markdown: findings, approach, files to change"},
                "items":{"type":"array","items":{"type":"string"},"description":"the ordered steps (the task list)"},
                "criteria":{"type":"array","items":{"type":"string"},
                  "description":"checkable acceptance criteria of the whole task"}},
               "required":["plan","items"],"additionalProperties":false}"""));
    }

    /** Read: it changes nothing but the mode, and the user is asked anyway, by the tool itself. */
    @Override
    public Risk risk() {
      return Risk.READ;
    }

    /** The wait for the user is the tool's run, so chat's tool timeout must not cut it. */
    @Override
    public int timeoutSeconds() {
      return cfg.approveTimeoutSeconds() + 10;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      if (tc.agent() != null) return ToolResult.error("only the main agent can propose the plan");
      String id = tc.origin();
      if (!on(cfg, overrides, id)) return ToolResult.error("plan mode is off: carry on with the task");
      String plan = args.get("plan").asText().strip();
      if (plan.isEmpty()) return ToolResult.error("plan is empty");
      List<String> items = strings(args.get("items"));
      List<String> criteria = args.has("criteria") ? strings(args.get("criteria")) : null;

      String where = write(id, plan, items, criteria, PlanFiles.PROPOSED, null);
      Optional<Planner.Answer> answer = ask(tc.signal(), id, plan, items, criteria);
      if (answer.isEmpty()) {
        return ToolResult.error("nobody answered: the plan is not approved yet" + where + ". Stop here: end your "
            + "answer with a short summary of the plan and ask the user to approve it or say what to change.");
      }
      if (!answer.get().approved()) {
        String feedback = answer.get().feedback();
        write(id, plan, items, criteria, PlanFiles.REJECTED, feedback);
        if (feedback.isEmpty()) {
          return ToolResult.error("the user did not approve the plan and gave no reason. Stop here: do not call "
              + "more tools; end your answer by asking what they want changed. You are still in plan mode.");
        }
        return ToolResult.error("the user did not approve the plan: " + feedback + ". You are still in plan mode: "
            + "revise the plan and propose it again.");
      }
      String saved = mark(id);
      set(ctx, cfg, overrides, id, false);
      String tasks = save(id, items, criteria);
      return ToolResult.ok("The user approved the plan" + saved + ". Plan mode is off: carry out the plan now with "
          + "all your tools" + tasks);
    }

    /** Writes the plan file; {@code " (saved in <path>)"} for the model, or why it could not be saved. */
    private String write(String id, String plan, List<String> items, List<String> criteria, String status,
        String feedback) {
      try {
        return " (saved in " + files.write(id, plan, items, criteria, status, feedback) + ")";
      } catch (IOException | RuntimeException e) {
        return " (the plan file could not be written: " + e.getMessage() + ")";
      }
    }

    /** Marks the plan file approved; a note for the model. */
    private String mark(String id) {
      try {
        return files.mark(id, PlanFiles.APPROVED).map(p -> " (saved in " + p + ")").orElse("");
      } catch (IOException | RuntimeException e) {
        return " (the plan file could not be updated: " + e.getMessage() + ")";
      }
    }

    /** Writes the plan as the task list; a note on how it went, for the model. */
    private String save(String id, List<String> items, List<String> criteria) {
      List<TodoItem> tasks = new ArrayList<>();
      for (String item : items) tasks.add(new TodoItem(item, TodoItem.Status.PENDING));
      try {
        Optional<Todo.Plan> written = Hooks.bail(ctx, Todo.WRITE, new Todo.Write(id, tasks, criteria));
        return written.isPresent()
            ? "; the task list now holds the steps: keep it current with todo_write."
            : ".";
      } catch (RuntimeException e) {
        return " (the task list could not be saved: " + e.getMessage() + ").";
      }
    }

    /**
     * Asks {@link Planner#APPROVE} and waits at most {@code approveTimeoutSeconds}; empty when nobody answered in
     * time. A cancelled turn (or the plugin's disposal) is thrown.
     */
    private Optional<Planner.Answer> ask(Signal caller, String conversationId, String plan, List<String> items,
        List<String> criteria) {
      CancelToken wait = new CancelToken();
      Disposable byCaller = caller.onCancel(wait::cancel);
      Disposable byOwner = ctx.signal().onCancel(wait::cancel);
      AtomicBoolean timedOut = new AtomicBoolean();
      int seconds = cfg.approveTimeoutSeconds();
      Task<Void> timer = ctx.spawn("plan-approval-timeout", s -> {
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
        Planner.Proposal proposal = new Planner.Proposal(newId(), conversationId, plan, items, criteria, wait);
        Optional<Planner.Answer> a = Hooks.bail(ctx, Planner.APPROVE, proposal);
        if (caller.isCancelled() || ctx.signal().isCancelled()) throw new CancelledException("plan approval cancelled");
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

    private static List<String> strings(JsonNode array) {
      List<String> out = new ArrayList<>();
      for (JsonNode n : array) {
        if (!n.asText().isBlank()) out.add(n.asText().strip());
      }
      return out;
    }

    private static String newId() {
      return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }
  }
}
