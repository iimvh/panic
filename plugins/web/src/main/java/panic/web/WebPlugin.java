package panic.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import context.ConfigPlugin;
import context.Context;
import context.Task;
import core.Disposable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import panic.interact.AskUser;
import panic.planner.Planner;
import panic.tools.Tools;

/**
 * The web front-end: an HTTP + SSE API ({@link Api}) and the built UI ({@link StaticFiles}).
 *
 * Every request runs as a task of this plugin, so disposing it (config change, app stopping) cancels the
 * requests in flight (a streaming answer is aborted) and waits for them before the server stops. Requests
 * that arrive while it is going away get 503.
 *
 * It answers {@code Tools.APPROVE} for sessions that have a chat or command stream open: the browser is
 * asked over SSE and answers with {@code POST /api/approve}. It answers {@code Planner.APPROVE} the same way
 * (SSE {@code plan}, {@code POST /api/plan}), and {@code AskUser.ASK}, the agent's questions (SSE
 * {@code question}, {@code POST /api/answer}).
 */
public final class WebPlugin implements ConfigPlugin<WebConfig> {

  private static final Duration HEARTBEAT = Duration.ofSeconds(5);

  private final Consumer<String> log;
  private final Duration heartbeat;

  /** {@code log} receives one line, {@code web: http://host:port}, when the server starts listening. */
  public WebPlugin(Consumer<String> log) {
    this(log, HEARTBEAT);
  }

  WebPlugin(Consumer<String> log, Duration heartbeat) {
    this.log = log;
    this.heartbeat = heartbeat;
  }

  @Override
  public Disposable apply(Context ctx, WebConfig cfg) {
    if (!cfg.enabled()) return null;
    Api api = new Api(ctx, heartbeat, Duration.ofSeconds(cfg.detachSeconds()));
    ctx.hook(Tools.APPROVE, api::approve);
    ctx.hook(Planner.APPROVE, api::plan);
    ctx.hook(AskUser.ASK, api::question);
    StaticFiles files = new StaticFiles();
    ctx.effect("server", signal -> {
      HttpServer server;
      try {
        server = HttpServer.create(new InetSocketAddress(cfg.host(), cfg.port()), 0);
      } catch (IOException e) {
        throw new IllegalStateException("web: cannot listen on " + cfg.host() + ":" + cfg.port() + ": " + e.getMessage(), e);
      }
      ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
      server.setExecutor(pool);
      server.createContext("/", ex -> serve(ctx, api, files, ex));
      server.start();
      InetSocketAddress at = server.getAddress();
      log.accept("web: http://" + hostOf(cfg.host()) + ":" + at.getPort());
      return () -> {
        server.stop(0);
        pool.shutdownNow();
      };
    });
    return null;
  }

  private static void serve(Context ctx, Api api, StaticFiles files, HttpExchange ex) {
    try (ex) {
      String path = ex.getRequestURI().getPath();
      Task<Void> task;
      try {
        task = ctx.spawn("http " + ex.getRequestMethod() + " " + path, signal -> {
          try {
            if (!api.route(ex, path, signal)) files.serve(ex, path);
          } catch (IOException e) {
            // the browser went away mid-response
          }
          return null;
        });
      } catch (RuntimeException closing) {
        Http.error(ex, 503, "shutting down");
        return;
      }
      try {
        task.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        // reported by the task; the response is broken
      } finally {
        task.dispose();
      }
    } catch (IOException ignored) {
      // the browser went away
    }
  }

  private static String hostOf(String host) {
    return host.contains(":") ? "[" + host + "]" : host;
  }
}
