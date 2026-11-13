package panic.command;

/** The arguments do not fit the command's parameters. */
public class UsageException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String usage;

  public UsageException(String message, String usage) {
    super(message);
    this.usage = usage;
  }

  /** e.g. {@code model [id]} */
  public String usage() {
    return usage;
  }
}
