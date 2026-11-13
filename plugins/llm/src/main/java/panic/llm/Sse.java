package panic.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Minimal server-sent-events reader for OpenAI-style streams: hands the payload of each event to
 * {@code onData}, and stops at {@code [DONE]} or end of stream. Comments ({@code : ...}) are skipped.
 */
public final class Sse {

  public static final String DONE = "[DONE]";

  private Sse() {
  }

  /** Returns true if the stream ended with [DONE]. */
  public static boolean read(BufferedReader in, Consumer<String> onData) throws IOException {
    StringBuilder data = new StringBuilder();
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isEmpty()) {
        if (dispatch(data, onData)) return true;
        continue;
      }
      if (line.startsWith(":")) continue;
      if (line.startsWith("data:")) {
        String value = line.substring(5);
        if (value.startsWith(" ")) value = value.substring(1);
        if (!data.isEmpty()) data.append('\n');
        data.append(value);
      }
      // other fields (event:, id:, retry:) are not used by chat completion streams
    }
    return dispatch(data, onData);
  }

  private static boolean dispatch(StringBuilder data, Consumer<String> onData) {
    if (data.isEmpty()) return false;
    String payload = data.toString();
    data.setLength(0);
    if (DONE.equals(payload.strip())) return true;
    onData.accept(payload);
    return false;
  }
}
