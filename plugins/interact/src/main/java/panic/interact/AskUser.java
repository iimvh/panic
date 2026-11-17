package panic.interact;

import context.HookKey;
import core.Signal;
import java.util.List;
import java.util.Objects;

/**
 * The public keys of asking the user. The agent calls tool {@value #TOOL}; plugin {@code ask-user} puts the
 * questions to {@link #ASK}, which front-ends answer by showing them (a popup with options in the web UI, numbered
 * options in the REPL). Front-ends never require {@code ask-user}.
 */
public final class AskUser {

  /** The tool the agent calls to ask the user. */
  public static final String TOOL = "ask_user";

  /** A choice the user can pick; {@code description} explains it (may be empty). */
  public record Option(String label, String description) {
    public Option {
      Objects.requireNonNull(label, "label");
      label = label.strip();
      if (label.isEmpty()) throw new IllegalArgumentException("an option needs a label");
      description = description == null ? "" : description.strip();
    }
  }

  /**
   * One question: the text, a short {@code header} (a chip in the UI, may be empty), its options and whether
   * several may be picked. Front-ends always add a free-text "Other" choice.
   */
  public record Question(String question, String header, List<Option> options, boolean multiSelect) {
    public Question {
      Objects.requireNonNull(question, "question");
      question = question.strip();
      header = header == null ? "" : header.strip();
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  /**
   * Questions the agent of {@code conversationId} (the conversation the user talks to) asks. {@code id} tells
   * requests apart (front-ends answer by it). {@code signal} is cancelled when the turn stops or the wait times
   * out: an answer that blocks must honour it.
   */
  public record Request(String id, String conversationId, List<Question> questions, Signal signal) {
    public Request {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(signal, "signal");
      questions = List.copyOf(questions);
    }
  }

  /** The answer to one question: the labels picked, and the user's own text ({@code other}, empty when none). */
  public record Answer(List<String> selected, String other) {
    public Answer {
      selected = selected == null ? List.of() : List.copyOf(selected);
      other = other == null ? "" : other.strip();
    }

    public boolean isEmpty() {
      return selected.isEmpty() && other.isEmpty();
    }
  }

  /** One answer per question, in order. All empty means the user skipped. */
  public record Answers(List<Answer> answers) {
    public Answers {
      answers = List.copyOf(answers);
    }

    public boolean skipped() {
      return answers.stream().allMatch(Answer::isEmpty);
    }
  }

  /**
   * Answered by front-ends, only for the conversations they are talking to (return null for others). No answer
   * or a timeout tells the agent the user did not answer.
   */
  public static final HookKey<Request, Answers> ASK = HookKey.of("ask_user.ask", Request.class, Answers.class);

  private AskUser() {
  }
}
