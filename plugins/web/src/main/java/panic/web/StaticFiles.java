package panic.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Serves the built UI from the classpath ({@code panic/web/ui/}), with SPA fallback to index.html. */
final class StaticFiles {

  static final String ROOT = "panic/web/ui/";

  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
      Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json"),
      Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("ico", "image/x-icon"),
      Map.entry("webp", "image/webp"), Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain; charset=utf-8"),
      Map.entry("webmanifest", "application/manifest+json"));

  private static final String NOT_BUILT = """
      <!doctype html><meta charset="utf-8"><title>Panic</title>
      <body style="font:16px system-ui;max-width:40rem;margin:4rem auto;padding:0 1rem">
      <h1>Panic</h1><p>The web UI was not built into this jar (built with <code>-PskipUi</code>?).</p>
      <p>Build it with <code>./gradlew :app:installDist</code> (needs Node), or run
      <code>npm run dev</code> in the UI folder. The API is up at <code>/api/state</code>.</p></body>""";

  private final ClassLoader loader = StaticFiles.class.getClassLoader();

  void serve(HttpExchange ex, String path) throws IOException {
    if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
      Http.text(ex, 405, "method not allowed");
      return;
    }
    String rel = path.startsWith("/") ? path.substring(1) : path;
    if (rel.contains("..") || rel.contains("\\")) {
      Http.text(ex, 400, "bad path");
      return;
    }
    String file = rel.isEmpty() || rel.endsWith("/") ? rel + "index.html" : rel;
    InputStream in = loader.getResourceAsStream(ROOT + file);
    if (in == null && !hasExtension(file)) {
      file = "index.html"; // client-side route
      in = loader.getResourceAsStream(ROOT + file);
    }
    if (in == null) {
      if (file.equals("index.html")) {
        Http.send(ex, 200, "text/html; charset=utf-8", NOT_BUILT.getBytes(StandardCharsets.UTF_8));
      } else {
        Http.text(ex, 404, "not found");
      }
      return;
    }
    byte[] body;
    try (InputStream res = in) {
      body = res.readAllBytes();
    }
    String cache = file.startsWith("_app/immutable/") ? "public, max-age=31536000, immutable" : "no-cache";
    ex.getResponseHeaders().set("Cache-Control", cache);
    Http.send(ex, 200, TYPES.getOrDefault(extension(file), "application/octet-stream"), body);
  }

  private static boolean hasExtension(String file) {
    int slash = file.lastIndexOf('/');
    return file.indexOf('.', slash + 1) >= 0;
  }

  private static String extension(String file) {
    int dot = file.lastIndexOf('.');
    return dot < 0 ? "" : file.substring(dot + 1).toLowerCase();
  }
}
