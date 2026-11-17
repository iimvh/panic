package panic.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import core.Disposable;
import core.Signal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import panic.mcp.McpClient.McpException;
import panic.tools.Processes;
import panic.util.Json;

/**
 * The MCP stdio transport: the server is a child process, and each message is one line of JSON on its stdin or
 * stdout. Lines on stdout that are not JSON objects or arrays are skipped (a server that logs there must not break
 * the session). The server's stderr goes to a temporary log, whose end explains a failure.
 *
 * Task {@code read} reads stdout until it ends; the server going away is reported as {@code exited (exit N)}.
 * {@link #close} stops the server the way the spec says: close its input, wait, SIGTERM, wait, SIGKILL, then
 * whatever it started that is still alive.
 */
final class StdioTransport implements Transport {

  private final McpConfig.Server server;
  private final AtomicBoolean down = new AtomicBoolean();
  private Process process;
  private Path stderr;

  StdioTransport(McpConfig.Server server) {
    this.server = server;
  }

  @Override
  public void open(Context ctx, Listener listener, Signal signal, long timeoutMillis) {
    try {
      stderr = Files.createTempFile("panic-mcp-", ".log");
      ProcessBuilder pb = new ProcessBuilder(server.commandLine())
          .directory(Path.of(server.cwd()).toAbsolutePath().normalize().toFile())
          .redirectError(stderr.toFile());
      pb.environment().putAll(server.env());
      process = pb.start();
    } catch (IOException | RuntimeException e) {
      deleteQuietly(stderr);
      stderr = null;
      throw new McpException("could not start " + String.join(" ", server.commandLine()) + ": " + e.getMessage());
    }
    ctx.spawn("read", s -> {
      Disposable onCancel = s.onCancel(this::close);
      try {
        read(listener);
      } finally {
        onCancel.dispose();
      }
      if (!down.get()) {
        try {
          process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        String tail = log();
        listener.lost("exited" + (process.isAlive() ? "" : " (exit " + process.exitValue() + ")"),
            tail.isEmpty() ? null : tail);
      }
      return null;
    });
  }

  private void read(Listener listener) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(),
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        if (line.isBlank()) continue;
        JsonNode msg = Json.parseOrNull(line);
        if (msg != null && (msg.isObject() || msg.isArray())) listener.message(msg);
      }
    } catch (IOException e) {
      // the pipe broke: the same as the server going away
    }
  }

  @Override
  public void send(JsonNode msg) {
    byte[] line;
    try {
      line = (Json.MAPPER.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new McpException("cannot encode the message: " + e.getMessage());
    }
    OutputStream out = process.getOutputStream();
    synchronized (out) {
      try {
        out.write(line);
        out.flush();
      } catch (IOException e) {
        throw new McpException("the server does not read its input any more: " + e.getMessage());
      }
    }
  }

  @Override
  public String log() {
    return stderr == null ? "" : Processes.tail(stderr, 500).strip().replace('\n', ' ');
  }

  @Override
  public boolean reconnects() {
    return false; // a local server that exited stays down until "mcp restart", as in Claude Code
  }

  @Override
  public void close() {
    if (!down.compareAndSet(false, true) || process == null) return;
    boolean interrupted = Thread.interrupted(); // wait even when stopping because of a cancel
    List<ProcessHandle> children = process.descendants().toList();
    try {
      try {
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // already closed
      }
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) Processes.kill(process);
      }
    } catch (InterruptedException e) {
      Processes.kill(process);
    } finally {
      children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
      deleteQuietly(stderr);
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private static void deleteQuietly(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // a temporary log left behind is harmless
    }
  }
}
