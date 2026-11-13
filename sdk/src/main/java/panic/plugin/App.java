package panic.plugin;

import context.ServiceKey;
import java.io.InputStream;
import panic.command.Out;

/**
 * The process around the plugin tree (provided by the app): its terminal, and a way to quit. It is provided
 * after every plugin is installed, so requiring it also means "start once the tree is complete".
 */
public interface App {

  ServiceKey<App> KEY = ServiceKey.of("app", App.class);

  InputStream in();

  Out out();

  /** Asks the app to stop (it disposes the whole tree). */
  void exit();
}
