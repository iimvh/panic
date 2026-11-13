package panic.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Reading System.in cannot be interrupted, so a task blocked on it would hold up disposal forever. One
 * daemon thread, outside the tree, reads lines into a queue; tasks wait on the queue, which is interruptible.
 */
public final class Stdin {

  private static final Optional<String> EOF = Optional.empty();

  private final BlockingQueue<Optional<String>> lines = new LinkedBlockingQueue<>();

  public Stdin(InputStream in) {
    Thread reader = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) lines.add(Optional.of(line));
      } catch (IOException ignored) {
        // treated as end of input
      }
      lines.add(EOF);
    }, "stdin");
    reader.setDaemon(true);
    reader.start();
  }

  /** The next line, or null at end of input. */
  public String readLine() throws InterruptedException {
    Optional<String> line = lines.take();
    if (line.isEmpty()) {
      lines.add(EOF); // keep answering EOF
      return null;
    }
    return line.get();
  }
}
