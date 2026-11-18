package panic.app;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

  private final ConfigLoader loader = new ConfigLoader(Map.of("OR_KEY", "sk-or", "MODEL", "x/y:free")::get, List.of());

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
  void environmentConnectsOpenRouterOnly() {
    ObjectNode cfg = new ConfigLoader(Map.of("OPENROUTER_API_KEY", "o", "MISTRAL_API_KEY", "m",
        "PANIC_MODEL", "some/model")::get, Panic.discoverSets()).fromEnvironment();
    assertEquals("openrouter", cfg.at("/llm/provider").asText());
    assertEquals("some/model", cfg.at("/llm/model").asText());
    assertEquals("o", cfg.at("/openrouter/apiKey").asText());
    assertTrue(cfg.at("/mistral").isMissingNode(), "custom providers come from panic.json only");
  }
}
