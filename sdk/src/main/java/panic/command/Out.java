package panic.command;

import java.io.PrintStream;

/** Where a command writes. A front-end decides what it is: the terminal today, a UI later. */
public interface Out {

  void print(String text);

  default void println(String line) {
    print(line + "\n");
  }

  default void println() {
    print("\n");
  }

  static Out of(PrintStream stream) {
    return text -> {
      stream.print(text);
      stream.flush();
    };
  }
}
