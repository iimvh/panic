package panic.verify;

/**
 * {@code prompt}: the reviewer's instructions (null or blank = {@link ReviewPlugin#PROMPT}). {@code maxTokens}:
 * the longest reply (non-positive = 16384): a reasoning model spends most of it thinking before the verdict, so a
 * small limit cuts the verdict off. {@code maxTranscript}: the most characters of the turn shown to the
 * reviewer, newest kept (non-positive = 12000). {@code temperature}: sent with the reviewer's request; null (the
 * default) sends none, so the model uses its own default (some reject a temperature of 0 unless {@code top_p} is
 * 1).
 */
public record ReviewConfig(String prompt, int maxTokens, int maxTranscript, Double temperature) {

  public static final ReviewConfig DEFAULT = new ReviewConfig(null, 0, 0, null);

  public ReviewConfig {
    prompt = prompt == null || prompt.isBlank() ? ReviewPlugin.PROMPT : prompt.strip();
    if (maxTokens <= 0) maxTokens = 16_384;
    if (maxTranscript <= 0) maxTranscript = 12_000;
  }

  public ReviewConfig(String prompt, int maxTokens, int maxTranscript) {
    this(prompt, maxTokens, maxTranscript, null);
  }
}
