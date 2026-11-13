package panic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import panic.util.Json;

class SchemaTest {

  private static List<String> check(String schema, String value) {
    return Schema.validate(Json.parseOrNull(schema), Json.parseOrNull(value));
  }

  @Test
  void types() {
    assertEquals(List.of(), check("{\"type\":\"integer\"}", "3"));
    assertEquals(List.of(), check("{\"type\":\"integer\"}", "3.0"));
    assertEquals(List.of("arguments: expected integer, got number"), check("{\"type\":\"integer\"}", "3.5"));
    assertEquals(List.of(), check("{\"type\":\"number\"}", "3.5"));
    assertEquals(List.of(), check("{\"type\":[\"string\",\"null\"]}", "null"));
    assertEquals(List.of("arguments: expected string or null, got boolean"),
        check("{\"type\":[\"string\",\"null\"]}", "true"));
    assertEquals(List.of("arguments: expected object, got array"), check("{\"type\":\"object\"}", "[]"));
  }

  @Test
  void objects() {
    String schema = """
        {"type":"object","properties":{"a":{"type":"string"},"b":{"type":"boolean"}},
         "required":["a"],"additionalProperties":false}""";
    assertEquals(List.of(), check(schema, "{\"a\":\"x\"}"));
    assertEquals(List.of("a: required"), check(schema, "{\"b\":true}"));
    assertEquals(List.of("a: required"), check(schema, "{\"a\":null}"));
    assertEquals(List.of("c: unknown property"), check(schema, "{\"a\":\"x\",\"c\":1}"));
    assertEquals(List.of(), check("{\"type\":\"object\"}", "{\"anything\":1}"), "extra properties allowed by default");
  }

  @Test
  void enumsAndBounds() {
    assertEquals(List.of(), check("{\"enum\":[\"a\",\"b\"]}", "\"b\""));
    assertEquals(List.of("arguments: must be one of [\"a\",\"b\"]"), check("{\"enum\":[\"a\",\"b\"]}", "\"c\""));
    assertEquals(List.of(), check("{\"enum\":[1]}", "1.0"));
    assertEquals(List.of("arguments: must be >= 1"), check("{\"minimum\":1}", "0"));
    assertEquals(List.of("arguments: must be <= 10"), check("{\"maximum\":10}", "11"));
  }

  @Test
  void nestedPaths() {
    String schema = """
        {"type":"object","properties":{"items":{"type":"array","items":{
          "type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}}""";
    assertEquals(List.of("items[1].name: expected string, got integer", "items[2].name: required"),
        check(schema, "{\"items\":[{\"name\":\"ok\"},{\"name\":1},{}]}"));
  }

  @Test
  void unknownKeywordsAreIgnored() {
    assertEquals(List.of(), check("{\"type\":\"string\",\"format\":\"email\",\"pattern\":\"^x\"}", "\"y\""));
  }
}
