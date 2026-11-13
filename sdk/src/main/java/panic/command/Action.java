package panic.command;

/** What a command does. */
@FunctionalInterface
public interface Action {

  void run(Call call) throws Exception;
}
