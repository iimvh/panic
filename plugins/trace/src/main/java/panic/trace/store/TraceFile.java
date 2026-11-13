package panic.trace.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import panic.trace.Span;

/**
 * The traces of one conversation as JSON lines, one span per line. A trace is appended in a single write once
 * its root has ended, so a crash leaves at most a torn last line. Reading skips damaged lines and spans whose
 * trace has no root, with a warning. Failures are reported through {@code warn}, never thrown: tracing must not
 * break anything.
 *
 * Files are read and written with plain streams, not channels: the writer task is interrupted when the plugin
 * stops, and an interrupt must not abort (and lose) a write in progress, as it would close a channel.
 */
final class TraceFile {

  static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,100}");

  private TraceFile() {
  }

  /**
   * The file name of conversation {@code id}: the id itself when it is short and safe, otherwise {@code ~} and
   * its base64url form ({@code ~} never appears in a safe id, so the two cannot collide). Same scheme as the
   * conversation files, so a session's trace file sits under the same name.
   */
  static String fileName(String id) {
    if (SAFE.matcher(id).matches()) return id + ".jsonl";
    return "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8))
        + ".jsonl";
  }

  /** What a file holds, and whether it needs rewriting to be clean (damaged lines, torn end). */
  record Read(List<Recorded> traces, boolean dirty) {
  }

  static Read read(Path file, Consumer<String> warn) {
    String text;
    try (InputStream in = new FileInputStream(file.toFile())) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (FileNotFoundException e) {
      if (!Files.exists(file)) return new Read(List.of(), false);
      warn.accept("[tracing] cannot read " + file + ": " + e);
      return new Read(List.of(), false);
    } catch (IOException e) {
      warn.accept("[tracing] cannot read " + file + ": " + e);
      return new Read(List.of(), false);
    }
    boolean dirty = !text.isEmpty() && !text.endsWith("\n");
    Map<String, List<Span>> byTrace = new LinkedHashMap<>();
    Map<String, Span> roots = new LinkedHashMap<>();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty()) continue;
      try {
        Span s = MAPPER.readValue(line, Span.class);
        if (s == null) throw new IllegalArgumentException("not a span");
        byTrace.computeIfAbsent(s.traceId(), k -> new ArrayList<>()).add(s);
        if (s.root()) roots.put(s.traceId(), s);
      } catch (JsonProcessingException | RuntimeException e) {
        dirty = true;
        warn.accept("[tracing] " + file + ":" + (i + 1) + " skipped: " + reason(e));
      }
    }
    List<Recorded> traces = new ArrayList<>();
    for (var e : byTrace.entrySet()) {
      Span root = roots.get(e.getKey());
      if (root == null) {
        dirty = true;
        warn.accept("[tracing] " + file + ": trace " + e.getKey() + " has no root span, skipped");
      } else {
        traces.add(new Recorded(root, e.getValue()));
      }
    }
    return new Read(traces, dirty);
  }

  static void append(Path file, Recorded trace, Consumer<String> warn) {
    try {
      write(file, lines(List.of(trace)), true);
    } catch (IOException e) {
      warn.accept("[tracing] cannot append to " + file + ": " + e);
    }
  }

  /** Replaces the file with {@code traces} (deletes it when there are none), through an atomic rename. */
  static void rewrite(Path file, List<Recorded> traces, Consumer<String> warn) {
    try {
      if (traces.isEmpty()) {
        Files.deleteIfExists(file);
        return;
      }
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      write(tmp, lines(traces), false);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      warn.accept("[tracing] cannot rewrite " + file + ": " + e);
    }
  }

  private static void write(Path file, String text, boolean append) throws IOException {
    try (OutputStream out = new FileOutputStream(file.toFile(), append)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String lines(List<Recorded> traces) {
    StringBuilder out = new StringBuilder();
    for (Recorded t : traces) {
      for (Span s : t.spans()) {
        try {
          out.append(MAPPER.writeValueAsString(s)).append('\n');
        } catch (JsonProcessingException e) {
          throw new IllegalStateException(e); // strings, numbers and booleans always serialize
        }
      }
    }
    return out.toString();
  }

  private static String reason(Exception e) {
    if (e instanceof JsonProcessingException j) return "invalid span (" + j.getOriginalMessage() + ")";
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
