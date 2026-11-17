package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import panic.util.Json;

/**
 * An MCP server for tests, started as its own JVM ({@code java -cp <test classpath> panic.mcp.FakeMcpServer
 * <dir>}). It writes its pid to {@code <dir>/pid} and every notification it gets to {@code <dir>/notes}. Tools:
 * {@code echo {text}}, {@code picture} (an image), {@code broken} (isError), {@code wait} (answers only when
 * cancelled... never), {@code die} (exits), {@code grow} (adds tool {@code extra} and says the list changed).
 * {@code tools/list} comes in two pages.
 */
public final class FakeMcpServer {

  private static boolean grown;

  private FakeMcpServer() {
  }

  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    Files.writeString(dir.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    System.err.println("fake server starting");
    out.println("this line is a log, not JSON");
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      JsonNode msg = Json.parseOrNull(line);
      if (msg == null) continue;
      String method = msg.path("method").asText("");
      if (!msg.has("id")) {
        Files.writeString(dir.resolve("notes"), method + " " + msg.path("params") + "\n", StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        continue;
      }
      JsonNode id = msg.get("id");
      JsonNode params = msg.path("params");
      ObjectNode result = Json.object();
      switch (method) {
        case "initialize" -> {
          result.put("protocolVersion", params.path("protocolVersion").asText());
          result.set("capabilities", Json.object().set("tools", Json.object().put("listChanged", true)));
          result.set("serverInfo", Json.object().put("name", "fake").put("version", "0.1"));
          result.put("instructions", "Use echo to repeat text.");
        }
        case "tools/list" -> {
          ArrayNode tools = result.putArray("tools");
          if (!params.has("cursor")) {
            tools.add(tool("echo", "Says the text back.",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}"));
            result.put("nextCursor", "2");
          } else {
            tools.add(tool("picture", "An image.", null));
            tools.add(tool("broken", "Always fails.", null));
            tools.add(tool("wait", "Never answers.", null));
            tools.add(tool("die", "Exits.", null));
            tools.add(tool("grow", "Adds a tool.", null));
            tools.add(tool("bad.name", "A name a model API refuses.", null));
            if (grown) tools.add(tool("extra", "Added later.", null));
          }
        }
        case "tools/call" -> {
          String name = params.path("name").asText();
          ArrayNode content = result.putArray("content");
          switch (name) {
            case "echo" -> content.addObject().put("type", "text").put("text", "echo: " + params.path("arguments").path("text").asText());
            case "picture" -> content.addObject().put("type", "image").put("mimeType", "image/png")
                .put("data", Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
            case "broken" -> {
              content.addObject().put("type", "text").put("text", "it broke");
              result.put("isError", true);
            }
            case "wait" -> {
              continue; // never answers: the client must cancel
            }
            case "die" -> System.exit(3);
            case "grow" -> {
              grown = true;
              content.addObject().put("type", "text").put("text", "grown");
              out.println(Json.object().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed"));
            }
            default -> {
              ObjectNode err = Json.object().put("jsonrpc", "2.0");
              err.set("id", id);
              err.set("error", Json.object().put("code", -32602).put("message", "unknown tool " + name));
              out.println(err);
              continue;
            }
          }
        }
        default -> {
          ObjectNode err = Json.object().put("jsonrpc", "2.0");
          err.set("id", id);
          err.set("error", Json.object().put("code", -32601).put("message", "no " + method));
          out.println(err);
          continue;
        }
      }
      ObjectNode reply = Json.object().put("jsonrpc", "2.0");
      reply.set("id", id);
      reply.set("result", result);
      out.println(reply);
    }
  }

  private static ObjectNode tool(String name, String description, String schema) {
    ObjectNode t = Json.object().put("name", name).put("description", description);
    t.set("inputSchema", schema == null ? Json.object().put("type", "object") : Json.parseOrNull(schema));
    return t;
  }
}
