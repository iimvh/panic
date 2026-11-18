package panic.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.command.Commands;
import panic.command.Session;
import panic.plugin.Configs;
import panic.util.CancelToken;

/** The {@code effort} command of {@code settings}, through {@link Commands#run} as front-ends call it. */
class SettingsPluginTest {

  private final Root root = Context.root("t");
  private final Map<String, Object> stored = new ConcurrentHashMap<>();
  private final StringBuilder out = new StringBuilder();
  private final Session session = new Session("s", out::append, new CancelToken());

  @BeforeEach
  void setUp() {
    stored.put("llm", new LlmConfig("a", "m1", 0.2, 4096));
    root.provide(Configs.KEY, new Configs() {
      @Override
      public <C> C get(String plugin, Class<C> type) {
        return type.cast(stored.get(plugin));
      }

      @Override
      public <C> void update(String plugin, C config) {
        stored.put(plugin, config);
      }
    });
    root.plugin("settings", new SettingsPlugin());
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private LlmConfig llm() {
    return (LlmConfig) stored.get("llm");
  }

  @Test
  void effortShowsSetsAndTurnsOff() {
    assertTrue(Commands.run(root, Llm.EFFORT_COMMAND, "", session));
    assertTrue(out.toString().contains("effort: off"), out.toString());

    Commands.run(root, Llm.EFFORT_COMMAND, "high", session);
    assertEquals("high", llm().reasoningEffort());
    assertEquals("m1", llm().model(), "the rest of the config is kept");
    assertEquals(4096, llm().maxTokens());

    Commands.run(root, Llm.EFFORT_COMMAND, "OFF", session);
    assertNull(llm().reasoningEffort());
  }

  @Test
  void switchingTheModelKeepsTheEffort() {
    Commands.run(root, Llm.EFFORT_COMMAND, "medium", session);
    stored.put("llm", llm().withModel("m2").withProvider("b"));
    assertEquals("medium", llm().reasoningEffort());
  }
}
