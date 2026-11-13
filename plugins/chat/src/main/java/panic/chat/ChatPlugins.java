package panic.chat;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the conversation store and the chat bot. */
public final class ChatPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of("conversations", ConversationsConfig.class, ConversationsConfig.DEFAULT,
            () -> new ConversationsPlugin(System.err::println)),
        PluginSpec.of("chat", ChatConfig.class, ChatConfig.DEFAULT, ChatPlugin::new));
  }
}
