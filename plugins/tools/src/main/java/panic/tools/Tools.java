package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import context.HookKey;
import context.Task;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import panic.command.Hooks;
import panic.util.CancelToken;
import panic.util.Json;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;

/**
 * The tool registry, built like {@code panic.command.Commands}: a plugin declares tools with {@link #register};
 * they are z hooks of that plugin, so they disappear when it is disposed, after any call in progress. Callers
 * (the agent loop, the {@code tool} command) list them with {@link #specs} and run them with {@link #call}.
 * No service is involved: offering a tool never ties a plugin's lifetime to its callers'.
 *
 * Every call passes the permission gate, inside the registered hook so no caller can skip it: after its
 * arguments are checked, the {@link #POLICY} hook says allow, ask or deny for the tool's {@link Risk}; an ask
 * goes to the {@link #APPROVE} hook, which front-ends answer by asking the user. No answer, a timeout or a
 * refusal denies the call.
 *
 * {@link #call} never throws for a bad call: unknown tool, invalid JSON, arguments that break the schema,
 * denied calls and exceptions from the tool all come back as {@link ToolResult#error}, for the model to read.
 * Only cancellation is thrown ({@code core.CancelledException}).
 */
public final class Tools {

  /** A request to run one tool call. */
  public record Invocation(ToolCall call, ToolContext context) {
    public Invocation {
      Objects.requireNonNull(call, "call");
      Objects.requireNonNull(context, "context");
    }
  }

  /** A tool as listed: what the model sees, its risk, and its own time limit (0 = the caller's). */
  public record Info(ToolSpec spec, Risk risk, int timeoutSeconds) {
    public Info {
      Objects.requireNonNull(spec, "spec");
      Objects.requireNonNull(risk, "risk");
      if (timeoutSeconds < 0) timeoutSeconds = 0;
    }

    public Info(ToolSpec spec, Risk risk) {
      this(spec, risk, 0);
    }
  }

  /**
   * The question put to the policy: may this call run? {@code conversationId} is the conversation the user talks
   * to ({@link ToolContext#origin}, also for a sub-agent's call), or null when nobody asks for a conversation (the
   * {@code tools} command listing).
   */
  public record Check(ToolCall call, Risk risk, String conversationId) {

    public Check(ToolCall call, Risk risk) {
      this(call, risk, null);
    }
  }

  /**
   * The policy's answer. {@code askTimeoutSeconds} bounds the wait for the user when it is {@code ASK};
   * {@code rule} names the rule that decided (shown in a denial), null when it was the tool's or risk's default.
   */
  public record Verdict(Permission permission, int askTimeoutSeconds, String rule) {

    public static final int DEFAULT_ASK_TIMEOUT_SECONDS = 120;

    public Verdict {
      Objects.requireNonNull(permission, "permission");
      if (askTimeoutSeconds <= 0) askTimeoutSeconds = DEFAULT_ASK_TIMEOUT_SECONDS;
    }

    public Verdict(Permission permission, int askTimeoutSeconds) {
      this(permission, askTimeoutSeconds, null);
    }

    /** The rule when no policy plugin runs: reading is allowed, anything else needs the user. */
    public static Verdict fallback(Risk risk) {
      return new Verdict(risk == Risk.READ ? Permission.ALLOW : Permission.ASK, DEFAULT_ASK_TIMEOUT_SECONDS);
    }
  }

  /**
   * A call that needs the user's approval. {@code conversationId} is the conversation the user talks to
   * ({@link ToolContext#origin}), also when a sub-agent makes the call; {@code agent} then names it (null for
   * the main agent). {@code signal} is cancelled when the caller gives up or the wait times out: an answer that
   * blocks must honour it.
   */
  public record Approval(String conversationId, ToolCall call, Risk risk, Signal signal, String agent) {

    public Approval(String conversationId, ToolCall call, Risk risk, Signal signal) {
      this(conversationId, call, risk, signal, null);
    }
  }

  /** Asks whether a conversation runs in bypass mode (YOLO: calls the policy would ask about just run). */
  public record BypassQuery(String conversationId) {
    public BypassQuery {
      Objects.requireNonNull(conversationId, "conversationId");
    }
  }

  /** {@code on}: the conversation bypasses approvals now; {@code byDefault}: what the config says for all of them. */
  public record Bypass(boolean on, boolean byDefault) {
  }

  /**
   * May the tool {@code name} (of {@code risk}) be offered to and run in {@code conversationId} (the conversation
   * the user talks to, {@link ToolContext#origin})? A mode such as plan mode answers with a {@link Refusal}.
   * {@code arguments} is the call's raw JSON when a call is checked (a rule on what a command does), null when the
   * tools are listed.
   */
  public record Gate(String conversationId, String name, Risk risk, String arguments) {
    public Gate {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(risk, "risk");
    }

    /** A listing: no call, so no {@code arguments}. */
    public Gate(String conversationId, String name, Risk risk) {
      this(conversationId, name, risk, null);
    }
  }

  /**
   * Why a {@link Gate} keeps a tool out: shown to the model in the error result of a call. {@code hide}: the tool
   * is also left out of what the model is offered. A mode that wants the model to learn why (plan mode) keeps it
   * visible, so a call gets the reason instead of the model guessing why it cannot act.
   */
  public record Refusal(String reason, boolean hide) {
    public Refusal {
      Objects.requireNonNull(reason, "reason");
    }

    /** A refusal that also hides the tool. */
    public Refusal(String reason) {
      this(reason, true);
    }
  }

  /** The user's answer to an {@link Approval}. */
  public enum Decision { ALLOW, DENY }

  public enum Listing { ALL }

  public static final HookKey<Listing, Info> LIST = HookKey.of("tool.list", Listing.class, Info.class);
  public static final HookKey<Invocation, ToolResult> RUN = HookKey.of("tool.run", Invocation.class, ToolResult.class);
  /** Answered by the policy plugin ({@code permissions}); without it {@link Verdict#fallback} applies. */
  public static final HookKey<Check, Verdict> POLICY = HookKey.of("tool.policy", Check.class, Verdict.class);

  /** Whether a conversation bypasses approvals; answered by {@code permissions}, so front-ends can show it. */
  public static final HookKey<BypassQuery, Bypass> BYPASS = HookKey.of("tool.bypass", BypassQuery.class,
      Bypass.class);
  /**
   * Collected for every call and listing of a conversation: any {@link Refusal} refuses a call, before the policy
   * is asked (bypass mode does not lift it), and one with {@code hide} leaves the tool out of the listing too.
   * Answer null to let the tool through.
   */
  public static final HookKey<Gate, Refusal> GATE = HookKey.of("tool.gate", Gate.class, Refusal.class);
  /**
   * Answered by front-ends, only for the conversations they are talking to (return null for others). No
   * answer means {@link Decision#DENY}.
   */
  public static final HookKey<Approval, Decision> APPROVE = HookKey.of("tool.approve", Approval.class,
      Decision.class);

  private Tools() {
  }

  /** Declares {@code tool} in {@code ctx}. Disposing the result (or ctx) removes it. */
  public static Disposable register(Context ctx, Tool tool) {
    ToolSpec spec = Objects.requireNonNull(tool.spec(), "spec");
    Info info = new Info(spec, Objects.requireNonNull(tool.risk(), "risk"), tool.timeoutSeconds());
    Disposable run = ctx.hook(RUN, inv -> {
      if (!inv.call().name().equals(spec.name())) return null;
      return run(ctx, tool, info, inv);
    });
    Disposable list = ctx.hook(LIST, q -> info);
    return () -> {
      list.dispose();
      run.dispose();
    };
  }

  /** The tools available right now, by name, with their risk. When two plugins declare a name, the first wins. */
  public static List<Info> infos(Context ctx) {
    Set<String> seen = new HashSet<>();
    List<Info> out = new ArrayList<>();
    for (Info info : ctx.collect(LIST, Listing.ALL)) {
      if (seen.add(info.spec().name())) out.add(info);
    }
    out.sort(Comparator.comparing(i -> i.spec().name()));
    return out;
  }

  /** What the model is told about the tools available right now (see {@link #infos}). */
  public static List<ToolSpec> specs(Context ctx) {
    return infos(ctx).stream().map(Info::spec).toList();
  }

  /**
   * What the model of {@code conversationId} (the conversation the user talks to) may be offered right now: the
   * tools no {@link #GATE} hides there (a tool refused without {@code hide} is offered, and its calls refused).
   */
  public static List<ToolSpec> specs(Context ctx, String conversationId) {
    return infos(ctx).stream().filter(i -> !hidden(ctx, conversationId, i)).map(Info::spec).toList();
  }

  /** Whether a {@link #GATE} hides {@code info} from {@code conversationId}; a gate that throws hides it. */
  private static boolean hidden(Context ctx, String conversationId, Info info) {
    try {
      return ctx.collect(GATE, new Gate(conversationId, info.spec().name(), info.risk())).stream()
          .anyMatch(Refusal::hide);
    } catch (RuntimeException e) {
      return true;
    }
  }

  /**
   * The first reason a {@link #GATE} gives to keep {@code info} out of {@code conversationId}, if any. A gate that
   * throws keeps the tool out too: a mode that cannot answer must not open the way to every tool.
   */
  public static Optional<String> refusal(Context ctx, String conversationId, Info info) {
    return refusal(ctx, conversationId, info, null);
  }

  /** As {@link #refusal(Context, String, Info)}, for a call with {@code arguments} (raw JSON). */
  public static Optional<String> refusal(Context ctx, String conversationId, Info info, String arguments) {
    List<Refusal> refusals;
    try {
      refusals = ctx.collect(GATE, new Gate(conversationId, info.spec().name(), info.risk(), arguments));
    } catch (RuntimeException e) {
      return Optional.of("tool gate failed: " + e.getMessage());
    }
    return refusals.stream().map(Refusal::reason).findFirst();
  }

  public static Optional<ToolSpec> find(Context ctx, String name) {
    return info(ctx, name).map(Info::spec);
  }

  /** The tool named {@code name} as listed right now, if any. */
  public static Optional<Info> info(Context ctx, String name) {
    return infos(ctx).stream().filter(i -> i.spec().name().equals(name)).findFirst();
  }

  /** What the policy says about {@code call} right now, for no conversation in particular. */
  public static Verdict verdict(Context ctx, ToolCall call, Risk risk) {
    return verdict(ctx, call, risk, null);
  }

  /** What the policy says about {@code call} of {@code conversationId} (null: none) right now. */
  public static Verdict verdict(Context ctx, ToolCall call, Risk risk, String conversationId) {
    return Hooks.bail(ctx, POLICY, new Check(call, risk, conversationId)).orElseGet(() -> Verdict.fallback(risk));
  }

  /** Runs {@code call} on the calling thread. See the class comment for what it returns. */
  public static ToolResult call(Context ctx, ToolCall call, ToolContext context) {
    context.signal().throwIfCancelled();
    return Hooks.bail(ctx, RUN, new Invocation(call, context))
        .orElseGet(() -> ToolResult.error("unknown tool: " + call.name()));
  }

  private static ToolResult run(Context owner, Tool tool, Info info, Invocation inv) {
    String name = info.spec().name();
    Optional<String> refused = refusal(owner, inv.context().origin(), info, inv.call().arguments());
    if (refused.isPresent()) return ToolResult.error(name + ": refused: " + refused.get());
    JsonNode args = Json.parseOrNull(inv.call().arguments());
    if (args == null) return ToolResult.error(name + ": arguments are not valid JSON: " + inv.call().arguments());
    List<String> problems = Schema.validate(info.spec().parameters(), args);
    if (!problems.isEmpty()) {
      return ToolResult.error(name + ": invalid arguments: " + String.join("; ", problems));
    }
    Verdict verdict = verdict(owner, inv.call(), info.risk(), inv.context().origin());
    switch (verdict.permission()) {
      case DENY -> {
        return ToolResult.error(name + ": denied by policy" + (verdict.rule() == null ? ""
            : " (rule " + verdict.rule() + "); do not try another way to do the same, tell the user if it is needed"));
      }
      case ASK -> {
        if (approve(owner, inv, info.risk(), verdict.askTimeoutSeconds()) != Decision.ALLOW) {
          return ToolResult.error(name + ": denied by the user");
        }
      }
      case ALLOW -> {
      }
    }
    inv.context().signal().throwIfCancelled();
    inv.context().started().run();
    // Cancelled by the caller or by the owner's disposal; the links are removed after the call so a
    // long-lived plugin does not collect one listener per call.
    CancelToken signal = new CancelToken();
    Disposable byCaller = inv.context().signal().onCancel(signal::cancel);
    Disposable byOwner = owner.signal().onCancel(signal::cancel);
    try {
      ToolResult result = tool.call(args, inv.context().forTool(signal));
      signal.throwIfCancelled();
      return result == null ? ToolResult.ok("") : result;
    } catch (CancelledException e) {
      throw e;
    } catch (RuntimeException e) {
      if (signal.isCancelled()) throw new CancelledException("tool " + name + " cancelled");
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return ToolResult.error(name + " failed: " + detail);
    } finally {
      byOwner.dispose();
      byCaller.dispose();
    }
  }

  /**
   * Asks {@link #APPROVE} and waits at most {@code timeoutSeconds}. A timeout or no answer denies; a cancelled
   * caller (or the owner's disposal) is thrown, so the turn stops instead of carrying on without the tool.
   */
  private static Decision approve(Context owner, Invocation inv, Risk risk, int timeoutSeconds) {
    Signal caller = inv.context().signal();
    CancelToken wait = new CancelToken();
    Disposable byCaller = caller.onCancel(wait::cancel);
    Disposable byOwner = owner.signal().onCancel(wait::cancel);
    AtomicBoolean timedOut = new AtomicBoolean();
    Task<Void> timer = owner.spawn("approval-timeout", s -> {
      try {
        Thread.sleep(timeoutSeconds * 1000L);
      } catch (InterruptedException e) {
        return null; // answered first
      }
      timedOut.set(true);
      wait.cancel();
      return null;
    });
    try {
      ToolContext tc = inv.context();
      Decision d = Hooks.bail(owner, APPROVE, new Approval(tc.origin(), inv.call(), risk, wait, tc.agent()))
          .orElse(Decision.DENY);
      if (caller.isCancelled() || owner.signal().isCancelled()) throw new CancelledException("approval cancelled");
      return timedOut.get() ? Decision.DENY : d;
    } catch (CancelledException e) {
      if (timedOut.get() && !caller.isCancelled() && !owner.signal().isCancelled()) return Decision.DENY;
      throw e;
    } finally {
      timer.dispose();
      byOwner.dispose();
      byCaller.dispose();
    }
  }
}
