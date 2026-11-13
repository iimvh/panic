package panic.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import panic.chat.ChatConfig;

class ConfigLoaderTest {

  private final ConfigLoader loader = new ConfigLoader(Map.of("OR_KEY", "sk-or", "MODEL", "x/y:free")::get);

  @Test
  void interpolatesEnvironmentInStrings() throws Exception {
    PanicConfig cfg = loader.parse("""
        {"providers":{"openrouter":{"apiKey":"${OR_KEY}"},"mistral":{"apiKey":"${MISSING}"}},
         "llm":{"provider":"openrouter","model":"${MODEL}","temperature":0.2},
         "chat":{"systemPrompt":"hi","maxHistory":10},
         "unknown":true}""");
    assertEquals("sk-or", cfg.provider("openrouter").apiKey());
    assertFalse(cfg.provider("mistral").hasKey(), "unset variable = empty = not connected");
    assertFalse(cfg.provider("other").hasKey());
    assertEquals("x/y:free", cfg.llm().model());
    assertEquals(0.2, cfg.llm().temperature());
    assertNull(cfg.llm().maxTokens());
    assertEquals(new ChatConfig("hi", 10), cfg.chat());
  }

  @Test
  void missingSectionsGetDefaults() throws Exception {
    PanicConfig cfg = loader.parse("{}");
    assertTrue(cfg.providers().isEmpty());
    assertNull(cfg.llm().provider());
    assertEquals(ChatConfig.DEFAULT, cfg.chat());
  }

  @Test
  void environmentOnlyPicksFirstProviderWithKey() {
    PanicConfig cfg = new ConfigLoader(Map.of("MISTRAL_API_KEY", "m", "PANIC_MODEL", "mistral-small-latest")::get)
        .fromEnvironment();
    assertEquals("mistral", cfg.llm().provider());
    assertEquals("mistral-small-latest", cfg.llm().model());
    assertTrue(cfg.provider("mistral").hasKey());
    assertFalse(cfg.provider("openrouter").hasKey());
  }

  @Test
  void keyIsNeverPrinted() throws Exception {
    assertFalse(loader.parse("{\"providers\":{\"openrouter\":{\"apiKey\":\"${OR_KEY}\"}}}").toString().contains("sk-or"));
  }
}
