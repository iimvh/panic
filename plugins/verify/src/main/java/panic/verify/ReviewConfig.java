package panic.verify;

/**
 * {@code prompt}: the reviewer's instructions (null or blank = {@link ReviewPlugin#PROMPT}). {@code maxTokens}:
 * the longest verdict (non-positive = 512). {@code maxTranscript}: the most characters of the turn shown to the
 * reviewer, newest kept (non-positive = 12000).
 */
public record ReviewConfig(String prompt, int maxTokens, int maxTranscript) {

  public static final ReviewConfig DEFAULT = new ReviewConfig(null, 0, 0);

  public ReviewConfig {
    prompt = prompt == null || prompt.isBlank() ? ReviewPlugin.PROMPT : prompt.strip();
    if (maxTokens <= 0) maxTokens = 512;
    if (maxTranscript <= 0) maxTranscript = 12_000;
  }
}
