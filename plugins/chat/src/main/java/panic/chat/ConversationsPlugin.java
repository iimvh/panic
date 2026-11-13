package panic.chat;

import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Provides {@link Conversations#KEY}, stored on disk under {@link ConversationsConfig#dir} (one JSON lines
 * file per conversation, see {@link ConversationFile}) or in memory only when {@code persist} is false. A
 * conversation is read from its file the first time it is asked for, then every change is written through.
 * One process per directory: two apps sharing it would interleave their writes.
 *
 * Requires nothing, so a provider or model switch keeps every history. A config change restarts it, and with
 * it whatever requires {@link Conversations#KEY}. Commands: {@code reset}, {@code chats [count]}, {@code forget <id>}
 * (see {@link ConversationStore}).
 */
public final class ConversationsPlugin implements ConfigPlugin<ConversationsConfig> {

  private final Consumer<String> warn;

  public ConversationsPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  @Override
  public Disposable apply(Context ctx, ConversationsConfig cfg) {
    Path dir = cfg.persist() ? directory(Path.of(cfg.dir()).toAbsolutePath().normalize()) : null;
    ConversationStore store = new ConversationStore(ctx, dir, warn);
    ctx.provide(Conversations.KEY, store);
    store.commands(ctx);
    store.hooks(ctx);
    return null;
  }

  /** {@code dir}, created if needed; null (memory only, with a warning) when it cannot be. */
  private Path directory(Path dir) {
    try {
      return Files.createDirectories(dir);
    } catch (IOException e) {
      warn.accept("[conversations] cannot create " + dir + ": " + e + "; history is kept in memory only");
      return null;
    }
  }
}
