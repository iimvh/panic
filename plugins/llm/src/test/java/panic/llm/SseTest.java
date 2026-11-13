package panic.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseTest {

  private static List<String> read(String stream, boolean[] done) throws Exception {
    List<String> out = new ArrayList<>();
    done[0] = Sse.read(new BufferedReader(new StringReader(stream)), out::add);
    return out;
  }

  @Test
  void eventsUntilDone() throws Exception {
    boolean[] done = {false};
    List<String> events = read(": keep-alive\n\ndata: {\"a\":1}\n\ndata:{\"b\":2}\n\ndata: [DONE]\n\ndata: late\n\n", done);
    assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), events);
    assertTrue(done[0]);
  }

  @Test
  void multiLineDataAndNoTrailingBlank() throws Exception {
    boolean[] done = {true};
    List<String> events = read("event: x\ndata: one\ndata: two", done);
    assertEquals(List.of("one\ntwo"), events);
    assertFalse(done[0]);
  }
}
