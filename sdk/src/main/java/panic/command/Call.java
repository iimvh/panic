package panic.command;

import core.Signal;
import java.util.Map;

/** One run of a command: its bound arguments and the session running it. */
public record Call(Command command, Map<String, String> args, Session session) {

  public Call {
    args = Map.copyOf(args);
  }

  /** The argument, or null when an optional parameter was not given. */
  public String arg(String name) {
    if (command.params().stream().noneMatch(p -> p.name().equals(name))) {
      throw new IllegalArgumentException("command '" + command.name() + "' has no parameter '" + name + "'");
    }
    return args.get(name);
  }

  public String argOr(String name, String fallback) {
    String value = arg(name);
    return value == null ? fallback : value;
  }

  public Out out() {
    return session.out();
  }

  public Signal signal() {
    return session.signal();
  }
}
