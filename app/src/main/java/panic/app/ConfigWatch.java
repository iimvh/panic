package panic.app;

import context.Plugin;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/** Watches the config file and applies every saved change to the running tree (hot reload). */
public final class ConfigWatch {

  private ConfigWatch() {
  }

  public static Plugin plugin(Panic panic, ConfigLoader loader, Path file, PrintStream log) {
    Path absolute = file.toAbsolutePath();
    Path dir = absolute.getParent();
    return ctx -> {
      WatchService watcher;
      try {
        watcher = FileSystems.getDefault().newWatchService();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      ctx.effect("watcher", signal -> () -> {
        try {
          watcher.close();
        } catch (IOException ignored) {
          // nothing left to release
        }
      });
      try {
        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      ctx.spawn("watch", signal -> {
        try {
          while (!signal.isCancelled()) {
            WatchKey key = watcher.take();
            boolean ours = false;
            for (WatchEvent<?> event : key.pollEvents()) {
              if (event.context() instanceof Path p && dir.resolve(p).equals(absolute)) ours = true;
            }
            key.reset();
            if (!ours) continue;
            Thread.sleep(150); // editors write in several steps: let the file settle
            while (true) { // drain the burst of events of this save
              WatchKey more = watcher.poll();
              if (more == null) break;
              more.pollEvents();
              more.reset();
            }
            try {
              panic.apply(loader.load(absolute));
              log.println("[config] reloaded " + absolute.getFileName());
            } catch (Exception e) {
              log.println("[config] not applied: " + e.getMessage());
            }
          }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
          // disposed
        }
        return null;
      });
      return null;
    };
  }
}
