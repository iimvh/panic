package panic.llm;

/** A provider call failed. {@code status} is the HTTP status, or 0 when the provider was unreachable. */
public class LlmException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  public LlmException(int status, String message) {
    super(message);
    this.status = status;
  }

  public LlmException(String message, Throwable cause) {
    super(message, cause);
    this.status = 0;
  }

  public int status() {
    return status;
  }
}
