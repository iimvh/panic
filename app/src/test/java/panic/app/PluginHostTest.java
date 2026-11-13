package panic.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigPlugin;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import panic.llm.Json;
import panic.llm.LlmConfig;
import panic.plugin.Configs;
import panic.plugin.PluginSpec;

class PluginHostTest {

  record Greeting(String text, int times) {
  }

  /** Records every apply as "name:config". */
  private final List<String> applied = new CopyOnWriteArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private Panic panic;

  @AfterEach
  void dispose() {
    if (panic != null) panic.dispose();
  }

  private PluginSpec<Greeting> greeter(String name) {
    return PluginSpec.of(name, Greeting.class, new Greeting("hi", 1), () -> (ConfigPlugin<Greeting>) (ctx, cfg) -> {
      applied.add(name + ":" + cfg.text() + "x" + cfg.times());
      return null;
    });
  }

  private static ObjectNode json(String text) throws Exception {
    return new ConfigLoader(n -> null).parse(text);
  }

  /** Keeps the discovered conversations store off the disk. */
  private static ObjectNode memoryOnly() {
    ObjectNode o = Json.object();
    o.putObject("conversations").put("persist", false);
    return o;
  }

  private Panic start(List<PluginSpec<?>> specs, String file, ObjectNode overrides) throws Exception {
    panic = Panic.start(specs, json(file), overrides, new Panic.Process(InputStream.nullInputStream(), t -> { }, () -> { }),
        (s, e) -> { }, warnings::add);
    return panic;
  }

  @Test
  void discoversEveryModulesPlugins() {
    List<String> names = Panic.discover().stream().map(PluginSpec::name).toList();
    assertTrue(names.containsAll(List.of("openrouter", "mistral", "llm", "settings", "conversations", "chat", "cli", "web")),
        names.toString());
  }

  @Test
  void sectionsByNameDefaultsAndFalse() throws Exception {
    start(List.of(greeter("a"), greeter("b"), greeter("c")),
        "{\"a\": {\"text\": \"yo\", \"times\": 2}, \"c\": false, \"zzz\": {}}", Json.object());
    assertEquals(List.of("a:yox2", "b:hix1"), applied, "b gets its default, c is off");
    assertTrue(warnings.get(0).contains("no plugin named 'zzz'"), warnings.toString());
  }

  @Test
  void reloadTouchesOnlyChangedSectionsAndCanInstallOrRemove() throws Exception {
    Panic p = start(List.of(greeter("a"), greeter("b"), greeter("c")), "{\"c\": false}", Json.object());
    applied.clear();
    p.apply(json("{\"a\": {\"text\": \"new\", \"times\": 1}, \"c\": {}}"));
    assertEquals(List.of("a:newx1", "c:nullx0"), applied);
    applied.clear();
    p.apply(json("{\"a\": {\"text\": \"new\", \"times\": 1}, \"c\": false}"));
    assertEquals(List.of(), applied, "nothing re-applied; c was removed");
    assertFalse(p.plugins().isEmpty());
  }

  @Test
  void overridesMergeFieldByField() throws Exception {
    ObjectNode overrides = Json.object();
    overrides.putObject("a").put("times", 9);
    overrides.putObject("b").put("times", 3);
    start(List.of(greeter("a"), greeter("b")), "{\"a\": {\"text\": \"yo\", \"times\": 2}, \"b\": false}", overrides);
    assertEquals(List.of("a:yox9", "b:nullx3"), applied, "override wins; it also turns a disabled section on");
  }

  @Test
  void runtimeChangesSurviveEditsOfOtherSections() throws Exception {
    Panic p = start(Panic.discover(), "{\"llm\": {\"provider\": \"openrouter\", \"model\": \"from-file\"}, \"cli\": {\"repl\": false}}",
        memoryOnly());
    Configs configs = configsOf(p);
    configs.update("llm", configs.get("llm", LlmConfig.class).withModel("runtime"));
    p.apply(json("{\"llm\": {\"provider\": \"openrouter\", \"model\": \"from-file\"}, \"cli\": {\"repl\": false},"
        + " \"chat\": {\"systemPrompt\": \"edited\"}}"));
    assertEquals("runtime", configs.get("llm", LlmConfig.class).model());
    p.apply(json("{\"llm\": {\"provider\": \"openrouter\", \"model\": \"file-2\"}, \"cli\": {\"repl\": false}}"));
    assertEquals("file-2", configs.get("llm", LlmConfig.class).model());
  }

  @Test
  void badConfigsAreReportedByPluginName() throws Exception {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> start(List.of(greeter("a")), "{\"a\": {\"times\": \"lots\"}}", Json.object()));
    assertTrue(e.getMessage().startsWith("bad config for 'a'"), e.getMessage());
    assertThrows(IllegalStateException.class, () -> start(List.of(greeter("a"), greeter("a")), "{}", Json.object()));
  }

  /** The Configs service, as a plugin sees it. */
  private static Configs configsOf(Panic p) {
    Configs[] out = new Configs[1];
    p.root().plugin("probe", context.Plugin.require(Configs.KEY).then(ctx -> {
      out[0] = ctx.get(Configs.KEY);
      return null;
    }));
    return out[0];
  }
}
