package panic.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

  private final ConfigLoader loader = new ConfigLoader(Map.of("OR_KEY", "sk-or", "MODEL", "x/y:free")::get);

  @Test
  void interpolatesEnvironmentInStrings() throws Exception {
    ObjectNode cfg = loader.parse("""
        {"openrouter": {"apiKey": "${OR_KEY}"}, "mistral": {"apiKey": "${MISSING}"},
         "llm": {"provider": "openrouter", "model": "${MODEL}", "tags": ["${MODEL}"]}}""");
    assertEquals("sk-or", cfg.at("/openrouter/apiKey").asText());
    assertEquals("", cfg.at("/mistral/apiKey").asText(), "unset variable = empty");
    assertEquals("x/y:free", cfg.at("/llm/model").asText());
    assertEquals("x/y:free", cfg.at("/llm/tags/0").asText());
  }

  @Test
  void readsTheOlderProvidersLayout() throws Exception {
    ObjectNode cfg = loader.parse("""
        {"providers": {"openrouter": {"apiKey": "${OR_KEY}"}, "mistral": {"apiKey": "m"}},
         "mistral": {"apiKey": "top-level wins"}}""");
    assertFalse(cfg.has("providers"));
    assertEquals("sk-or", cfg.at("/openrouter/apiKey").asText());
    assertEquals("top-level wins", cfg.at("/mistral/apiKey").asText());
  }

  @Test
  void environmentOnlyPicksFirstProviderWithKey() {
    ObjectNode cfg = new ConfigLoader(Map.of("MISTRAL_API_KEY", "m", "PANIC_MODEL", "mistral-small-latest")::get)
        .fromEnvironment();
    assertEquals("mistral", cfg.at("/llm/provider").asText());
    assertEquals("mistral-small-latest", cfg.at("/llm/model").asText());
    assertEquals("m", cfg.at("/mistral/apiKey").asText());
    assertTrue(cfg.at("/openrouter").isMissingNode());
  }
}
