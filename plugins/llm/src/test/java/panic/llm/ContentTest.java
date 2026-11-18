package panic.llm;

import panic.util.Json;
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

  @Test
  void reasoningFieldsAndThinkingParts() {
    assertEquals("a", Content.reasoning(Json.parseOrNull("{\"reasoning\":\"a\"}")));
    assertEquals("b", Content.reasoning(Json.parseOrNull("{\"reasoning_content\":\"b\"}")));
    assertEquals("a", Content.reasoning(Json.parseOrNull("{\"reasoning\":\"a\",\"reasoning_content\":\"a\"}")),
        "the same thought sent in both fields counts once");
    assertEquals("hmm", Content.reasoning(Json.parseOrNull("""
        {"content":[{"type":"thinking","thinking":[{"type":"text","text":"hmm"}]},{"type":"text","text":"Hi"}]}""")));
    assertEquals("", Content.reasoning(Json.parseOrNull("{\"content\":\"Hi\",\"reasoning\":null}")));
    assertEquals("", Content.reasoning(null));
  }
}
