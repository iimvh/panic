package panic.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigPlugin;
import context.Context;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import panic.chat.Chat;
import panic.command.Commands;
import panic.util.Json;
import panic.llm.ToolSpec;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolProgress;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.trace.Trace;

/**
 * Sub-agents: the tool {@code agent {agent, task, instructions?}} hands a self-contained task to a new agent
 * loop, a delegated ask ({@link Chat.Delegate}) with the profile's prompt, tools and limits, in a conversation
 * of its own ({@code <conversation>~<agent>-<id>}, saved like any other). The caller gets only the sub-agent's
 * final answer, and its tokens in {@link ToolResult#usage}; what the sub-agent writes and the tools it calls are
 * reported as progress while it runs. Several {@code agent} calls in one reply run at once when
 * {@code chat.parallelTools} allows it, at most {@link AgentsConfig#maxParallel} in the whole app.
 *
 * Requires nothing: it asks {@link Chat#ASK}, so with no LLM connected a call is an error result, and a switch
 * of model never restarts it. Each tool call of a sub-agent passes the permission gate on its own and is
 * approved in the conversation of the user ({@link ToolContext#origin}), so the {@code agent} tool itself is a
 * {@code read}. Disposing the plugin cancels the sub-agents running and waits for them (the tool's signal is the
 * ask's). Command: {@code agents}.
 */
public final class AgentsPlugin implements ConfigPlugin<AgentsConfig> {

  /** The name of the tool. */
  public static final String TOOL = "agent";

  /** What every sub-agent is told before its role or instructions. */
  static final String BASE_PROMPT = "You are a sub-agent. Another agent gave you one task and will only see your "
      + "final answer, not your tool calls or the files you read. You cannot ask the user or the other agent "
      + "anything. Do the task with your tools, then answer with exactly what was asked, so the other agent can act "
      + "on it without redoing your work: for each finding, the file path (and line numbers when you know them) and "
      + "the relevant code or text quoted as it is, not paraphrased; values and results as they came; and what you "
      + "looked for but did not find. Leave out what does not bear on the task. Be complete but concise.";

  @Override
  public Disposable apply(Context ctx, AgentsConfig cfg) {
    if (cfg.any()) Tools.register(ctx, new AgentTool(ctx, cfg));
    Commands.command(ctx, "agents", "list the sub-agents the bot can start", call -> {
      if (!cfg.any()) {
        call.out().println("no sub-agents (section \"agents\" of panic.json)");
        return;
      }
      if (cfg.general()) call.out().println(AgentsConfig.GENERAL + "  every tool  instructions written by the bot");
      for (Map.Entry<String, AgentsConfig.Profile> e : cfg.profiles().entrySet()) {
        AgentsConfig.Profile p = e.getValue();
        StringBuilder line = new StringBuilder(e.getKey()).append("  ")
            .append(p.tools() == null ? "every tool" : "tools: " + listed(ctx, p.tools()));
        if (p.maxSteps() > 0) line.append("  maxSteps ").append(p.maxSteps());
        if (p.maxTokens() > 0) line.append("  maxTokens ").append(p.maxTokens());
        call.out().println(line.append("  ").append(p.description()).toString());
      }
      call.out().println("(depth " + cfg.maxDepth() + ", " + cfg.maxParallel() + " at once, timeout "
          + cfg.timeoutSeconds() + "s)");
    });
    return null;
  }

  /**
   * The tools a profile's {@code tools} stand for now: its names, and every tool registered with the risk of each
   * group it names ({@link AgentsConfig#GROUPS}). Resolved when a sub-agent starts, so later tools are covered.
   */
  static Set<String> resolve(Context ctx, List<String> tools) {
    Set<String> out = new TreeSet<>();
    List<Tools.Info> infos = null;
    for (String t : tools) {
      Risk group = AgentsConfig.GROUPS.get(t);
      if (group == null) {
        out.add(t);
        continue;
      }
      if (infos == null) infos = Tools.infos(ctx);
      for (Tools.Info i : infos) {
        if (i.risk() == group) out.add(i.spec().name());
      }
    }
    return out;
  }

  /** For the {@code agents} command: the tools resolved now, and the names no registered tool has (drift, typos). */
  private static String listed(Context ctx, List<String> tools) {
    Set<String> known = new HashSet<>();
    for (ToolSpec s : Tools.specs(ctx)) known.add(s.name());
    List<String> have = new ArrayList<>();
    List<String> unknown = new ArrayList<>();
    for (String t : resolve(ctx, tools)) (known.contains(t) ? have : unknown).add(t);
    String line = have.isEmpty() ? "none" : String.join(", ", have);
    return unknown.isEmpty() ? line : line + " (unknown: " + String.join(", ", unknown) + ")";
  }

  /** For the main agent, in the tool's description: what a profile can use, groups in words. */
  private static String described(List<String> tools) {
    if (tools == null) return "every tool";
    List<String> parts = new ArrayList<>();
    for (String t : tools) {
      Risk group = AgentsConfig.GROUPS.get(t);
      parts.add(group == null ? t : "every " + group.name().toLowerCase(Locale.ROOT) + " tool");
    }
    return parts.isEmpty() ? "no tools" : String.join(", ", parts);
  }

  private static final class AgentTool implements Tool {

    private final Context ctx;
    private final AgentsConfig cfg;
    private final Semaphore slots;
    /** How deep each running sub-agent's conversation is (1 = started by the main agent). */
    private final Map<String, Integer> depths = new ConcurrentHashMap<>();

    AgentTool(Context ctx, AgentsConfig cfg) {
      this.ctx = ctx;
      this.cfg = cfg;
      this.slots = new Semaphore(cfg.maxParallel(), true);
    }

    @Override
    public ToolSpec spec() {
      ObjectNode schema = Json.object().put("type", "object");
      ObjectNode props = schema.putObject("properties");
      ArrayNode names = props.putObject("agent").put("type", "string")
          .put("description", "which sub-agent").putArray("enum");
      if (cfg.general()) names.add(AgentsConfig.GENERAL);
      cfg.profiles().keySet().forEach(names::add);
      props.putObject("task").put("type", "string").put("description",
          "the whole task, self-contained: the sub-agent sees nothing of this conversation");
      if (cfg.general()) {
        props.putObject("instructions").put("type", "string").put("description",
            "for '" + AgentsConfig.GENERAL + "' only: how it should work (role, constraints, what to answer)");
      }
      schema.putArray("required").add("agent").add("task");
      schema.put("additionalProperties", false);

      StringBuilder list = new StringBuilder();
      if (cfg.general()) {
        list.append("\n- ").append(AgentsConfig.GENERAL).append(": any task, every tool; describe how to work in ")
            .append("`instructions`");
      }
      // with what each can use, so the task asks only for what it can do (no grep to a profile without it)
      cfg.profiles().forEach((n, p) -> list.append("\n- ").append(n).append(" (tools: ").append(described(p.tools()))
          .append("): ").append(p.description()));
      return new ToolSpec(TOOL, "Hand a self-contained task to a sub-agent, which works on it with its own tools "
          + "and context and answers with its result only. Use it for broad work whose details you do not need in "
          + "your own context: searching many files or folders for something, researching, or a separate part of a "
          + "larger change; several calls in one reply run at the same time. Do not use it to read a file whose "
          + "path you know, or to look at a few files: read them yourself, it is faster and cheaper. In the task, "
          + "state the goal, the scope (folders, what to skip) and what to return (e.g. paths with the relevant "
          + "code quoted). Its answer is your evidence: do not read again the files it quoted unless you are going "
          + "to change them. Sub-agents:" + list, schema);
    }

    @Override
    public Risk risk() {
      return Risk.READ; // every tool call of the sub-agent passes the gate itself
    }

    @Override
    public int timeoutSeconds() {
      return cfg.timeoutSeconds();
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      String name = args.get("agent").asText();
      String task = args.get("task").asText().strip();
      if (task.isEmpty()) return ToolResult.error(TOOL + ": the task is empty");
      int depth = depths.getOrDefault(tc.conversationId(), 0);
      if (depth >= cfg.maxDepth()) {
        return ToolResult.error(TOOL + ": a sub-agent at depth " + depth + " cannot start another one "
            + "(agents.maxDepth = " + cfg.maxDepth() + "); do the work yourself");
      }
      Chat.Delegate delegate = delegate(name, args.path("instructions").asText(""), depth, tc);
      if (delegate == null) return ToolResult.error(TOOL + ": unknown sub-agent " + name);

      acquire(tc.signal());
      String child = tc.conversationId() + "~" + name + "-" + Trace.newId().substring(0, 8);
      depths.put(child, depth + 1);
      try {
        Optional<Chat.Reply> reply = Chat.ask(ctx, new Chat.Ask(child, task, tc.signal(),
            text -> tc.progress().accept(ToolProgress.text(name, text)),
            step -> tc.progress().accept(ToolProgress.step(step.nested() ? step.agent() : name, step.call(),
                step.result())),
            null, null, tc.traceId(), null, delegate,
            thought -> tc.progress().accept(ToolProgress.reasoning(name, thought))));
        if (reply.isEmpty()) return ToolResult.error(TOOL + ": no model is connected");
        Chat.Reply r = reply.get();
        String text = r.text().isBlank() ? "(no answer)" : r.text();
        if (Chat.DONE.equals(r.stop())) return new ToolResult(text, false, r.usage());
        return new ToolResult(name + " stopped before it finished (" + r.stop() + ", " + r.steps() + " steps). "
            + "Its last answer:\n" + text, true, r.usage());
      } finally {
        depths.remove(child);
        slots.release();
      }
    }

    /** The delegate for profile {@code name}, or null when there is none. */
    private Chat.Delegate delegate(String name, String instructions, int depth, ToolContext tc) {
      String prompt;
      List<String> tools;
      int maxSteps = 0;
      int maxTokens = 0;
      if (cfg.general() && name.equals(AgentsConfig.GENERAL)) {
        prompt = instructions.isBlank() ? BASE_PROMPT : BASE_PROMPT + "\n\n## Instructions\n" + instructions.strip();
        tools = null;
      } else {
        AgentsConfig.Profile p = cfg.profiles().get(name);
        if (p == null) return null;
        prompt = p.systemPrompt() != null ? p.systemPrompt() : BASE_PROMPT + "\n\n## Your role\n" + p.description();
        tools = p.tools();
        maxSteps = p.maxSteps();
        maxTokens = p.maxTokens();
      }
      Set<String> allowed = null;
      boolean last = depth + 1 >= cfg.maxDepth();
      if (tools != null) {
        allowed = new HashSet<>(resolve(ctx, tools));
      } else if (last) {
        allowed = new HashSet<>();
        for (ToolSpec s : Tools.specs(ctx)) allowed.add(s.name());
      }
      if (last && allowed != null) allowed.remove(TOOL); // the deepest sub-agents cannot start more
      return new Chat.Delegate(tc.origin(), name, prompt, allowed, maxSteps, maxTokens, tc.spanId());
    }

    /** Waits for a free slot (at most {@code maxParallel} sub-agents at once); a cancelled caller is thrown. */
    private void acquire(Signal signal) {
      try {
        while (!slots.tryAcquire(100, TimeUnit.MILLISECONDS)) signal.throwIfCancelled();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancelledException("interrupted while waiting for a sub-agent slot");
      }
      if (signal.isCancelled()) {
        slots.release();
        signal.throwIfCancelled();
      }
    }
  }
}
