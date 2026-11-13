package panic.cli;

/** {@code repl}: read commands and messages from the terminal; false: only declare {@code help}. */
public record CliConfig(boolean repl) {

  public static final CliConfig DEFAULT = new CliConfig(true);
}
