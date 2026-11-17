package panic.todo;

import context.HookKey;
import java.util.List;
import java.util.Objects;

/**
 * The public keys of the task list. Other plugins read a conversation's plan through {@link #PLAN} instead of
 * requiring {@code todo}: with no answer there is simply no plan.
 */
public final class Todo {

  /** Which conversation's plan to read. */
  public record Query(String conversationId) {
    public Query {
      Objects.requireNonNull(conversationId, "conversationId");
    }
  }

  /**
   * A conversation's tasks, in order, and the acceptance criteria its final answer must meet (the definition of
   * done the agent wrote before working).
   */
  public record Plan(List<TodoItem> items, List<String> criteria) {
    public static final Plan EMPTY = new Plan(List.of(), List.of());

    public Plan {
      items = items == null ? List.of() : List.copyOf(items);
      criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    public boolean isEmpty() {
      return items.isEmpty() && criteria.isEmpty();
    }
  }

  /**
   * Replaces the plan of a conversation, as {@code todo_write} does: {@code items} in order, and {@code criteria}
   * unless null (null keeps the current ones). Used by plugins that make the plan for the agent (an approved plan
   * of {@code planner}).
   */
  public record Write(String conversationId, List<TodoItem> items, List<String> criteria) {
    public Write {
      Objects.requireNonNull(conversationId, "conversationId");
      items = items == null ? List.of() : List.copyOf(items);
      criteria = criteria == null ? null : List.copyOf(criteria);
    }
  }

  /** The plan of a conversation; answered by plugin {@code todo}, with null when the plan is empty. */
  public static final HookKey<Query, Plan> PLAN = HookKey.of("todo.plan", Query.class, Plan.class);

  /**
   * Answered by plugin {@code todo} with the plan as saved. Too many tasks or criteria throw
   * {@link IllegalArgumentException}, a failed write {@link java.io.UncheckedIOException}; no answer means no
   * {@code todo} is running.
   */
  public static final HookKey<Write, Plan> WRITE = HookKey.of("todo.write", Write.class, Plan.class);

  private Todo() {
  }
}
