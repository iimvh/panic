package panic.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContentTest {

  @Test
  void string() {
    assertEquals("hi", Content.text(Json.parseOrNull("\"hi\"")));
  }

  @Test
  void partsKeepOnlyText() {
    String parts = """
        [{"type":"thinking","thinking":[{"type":"text","text":"hmm"}]},
         {"type":"text","text":"Hello"},{"type":"text","text":" world"}]""";
    assertEquals("Hello world", Content.text(Json.parseOrNull(parts)));
  }

  @Test
  void missingOrNull() {
    assertEquals("", Content.text(null));
    assertEquals("", Content.text(Json.parseOrNull("null")));
    assertEquals("", Content.text(Json.object().path("content")));
  }
}
