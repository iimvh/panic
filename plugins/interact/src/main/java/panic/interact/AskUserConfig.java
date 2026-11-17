package panic.interact;

/**
 * Asking the user. {@code timeoutSeconds}: how long questions wait for an answer (default 900).
 * {@code maxQuestions}: questions per call (default 4). {@code maxOptions}: options per question (default 6; at
 * least 2 are always required).
 */
public record AskUserConfig(int timeoutSeconds, int maxQuestions, int maxOptions) {

  public static final AskUserConfig DEFAULT = new AskUserConfig(0, 0, 0);

  public AskUserConfig {
    if (timeoutSeconds <= 0) timeoutSeconds = 900;
    if (maxQuestions <= 0) maxQuestions = 4;
    if (maxOptions <= 0) maxOptions = 6;
    if (maxOptions < 2) maxOptions = 2;
  }
}
