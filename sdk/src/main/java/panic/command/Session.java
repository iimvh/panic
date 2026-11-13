package panic.command;

import core.Signal;
import java.util.Objects;

/**
 * Who runs a command: {@code id} identifies the user's session (the chat uses it as the conversation id),
 * {@code out} receives the output, {@code signal} cancels the command.
 */
public record Session(String id, Out out, Signal signal) {

  public Session {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(signal, "signal");
  }
}
