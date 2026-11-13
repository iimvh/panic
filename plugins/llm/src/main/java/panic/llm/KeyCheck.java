package panic.llm;

/** Verdict of checking an API key against its provider. */
public record KeyCheck(boolean ok, String detail) {
}
