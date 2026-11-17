package panic.planner;

import context.EventKey;
import context.HookKey;
import core.Signal;
import java.util.List;
import java.util.Objects;

/**
 * The public keys of plan mode. Front-ends read the mode through {@link #MODE}, switch it with command
 * {@code plan on|off}, and answer {@link #APPROVE} when the agent proposes its plan; they never require the
 * {@code planner} plugin.
 */
public final class Planner {

  /** The tool the agent calls to propose its plan and leave plan mode. */
  public static final String EXIT_TOOL = "exit_plan_mode";

  /** Asks whether a conversation is in plan mode. */
  public record ModeQuery(String conversationId) {
    public ModeQuery {
      Objects.requireNonNull(conversationId, "conversationId");
    }
  }

  /** {@code on}: the conversation is in plan mode now; {@code byDefault}: what the config says for all of them. */
  public record Mode(boolean on, boolean byDefault) {
  }

  /**
   * The plan the agent proposes in {@code conversationId} (the conversation the user talks to): {@code plan} in
   * markdown, the ordered {@code items} that become its task list and the acceptance {@code criteria}. {@code id}
   * tells proposals apart (front-ends answer by it). {@code signal} is cancelled when the turn stops or the wait
   * times out: an answer that blocks must honour it.
   */
  public record Proposal(String id, String conversationId, String plan, List<String> items, List<String> criteria,
      Signal signal) {
    public Proposal {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(signal, "signal");
      items = items == null ? List.of() : List.copyOf(items);
      criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }
  }

  /** The user's answer: approved, or not with {@code feedback} for the agent (may be blank). */
  public record Answer(boolean approved, String feedback) {
    public static final Answer APPROVED = new Answer(true, null);

    public Answer {
      feedback = feedback == null ? "" : feedback.strip();
    }

    public static Answer rejected(String feedback) {
      return new Answer(false, feedback);
    }
  }

  /** A conversation entered or left plan mode (by command, or by an approved plan). */
  public record ModeChanged(String conversationId, boolean on) {
  }

  /** Whether a conversation is in plan mode; answered by {@code planner}. */
  public static final HookKey<ModeQuery, Mode> MODE = HookKey.of("planner.mode", ModeQuery.class, Mode.class);

  /**
   * Answered by front-ends, only for the conversations they are talking to (return null for others). No answer
   * or a timeout means not approved: the conversation stays in plan mode.
   */
  public static final HookKey<Proposal, Answer> APPROVE = HookKey.of("planner.approve", Proposal.class,
      Answer.class);

  public static final EventKey<ModeChanged> CHANGED = EventKey.of("planner.changed", ModeChanged.class);

  private Planner() {
  }
}
