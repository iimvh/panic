package panic.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import panic.llm.Json;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;

/**
 * One conversation stored as JSON lines: an optional {@code {"summary": ...}} line first, then one message per
 * line. An exchange is appended in a single write, so a crash leaves at most a torn last line. Compaction
 * rewrites the file through a temporary file and an atomic rename. Loading skips lines that are not valid
 * messages and turns that would be rejected by a provider (a tool call without its result), with a warning,
 * and then rewrites the file clean so later appends start on a fresh line.
 *
 * Write failures are reported through {@code warn} and never thrown: the conversation goes on in memory.
 */
final class ConversationFile implements Conversation.Journal {

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,100}");

  private final Path file;
  private final Consumer<String> warn;

  private ConversationFile(Path file, Consumer<String> warn) {
    this.file = file;
    this.warn = warn;
  }

  /**
   * The file name of conversation {@code id}: the id itself when it is short and safe, otherwise {@code ~} and
   * its base64url form ({@code ~} never appears in a safe id, so the two cannot collide).
   */
  static String fileName(String id) {
    if (SAFE.matcher(id).matches()) return id + ".jsonl";
    return "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8))
        + ".jsonl";
  }

  /** Loads conversation {@code id} from {@code dir} (empty when it has no file yet). */
  static Conversation load(Path dir, String id, Consumer<String> warn) {
    ConversationFile store = new ConversationFile(dir.resolve(fileName(id)), warn);
    String text;
    try {
      text = new String(Files.readAllBytes(store.file), StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return new Conversation(id, store, null, List.of());
    } catch (IOException e) {
      warn.accept("[conversations] cannot read " + store.file + ": " + e + "; this conversation is kept in memory only");
      return new Conversation(id, Conversation.Journal.NONE, null, List.of());
    }
    String summary = null;
    List<Message> read = new ArrayList<>();
    boolean dirty = !text.isEmpty() && !text.endsWith("\n");
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty()) continue;
      try {
        JsonNode node = Json.MAPPER.readTree(line);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("not a JSON object");
        if (node.has("summary") && !node.has("role")) {
          summary = text(node, "summary");
        } else {
          read.add(decode(node));
        }
      } catch (JsonProcessingException | RuntimeException e) {
        dirty = true;
        warn.accept("[conversations] " + store.file + ":" + (i + 1) + " skipped: " + reason(e));
      }
    }
    List<Message> messages = wellFormed(read);
    if (messages.size() != read.size()) {
      dirty = true;
      warn.accept("[conversations] " + store.file + ": dropped " + (read.size() - messages.size())
          + " message(s) of incomplete turns");
    }
    if (dirty) store.rewrite(summary, messages);
    return new Conversation(id, store, summary, messages);
  }

  @Override
  public void append(List<Message> exchange) {
    StringBuilder out = new StringBuilder();
    for (Message m : exchange) out.append(line(encode(m)));
    try {
      Files.writeString(file, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      warn.accept("[conversations] cannot append to " + file + ": " + e);
    }
  }

  @Override
  public void rewrite(String summary, List<Message> messages) {
    StringBuilder out = new StringBuilder();
    if (summary != null) out.append(line(Json.object().put("summary", summary)));
    for (Message m : messages) out.append(line(encode(m)));
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      Files.writeString(tmp, out, StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      warn.accept("[conversations] cannot rewrite " + file + ": " + e);
    }
  }

  @Override
  public void delete() {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      warn.accept("[conversations] cannot delete " + file + ": " + e);
    }
  }

  /**
   * The messages of every complete turn. A turn starts at a user message; it is kept only when each tool call is
   * answered by tool messages right after it, and every tool message answers such a call. Messages before the
   * first user message are dropped.
   */
  static List<Message> wellFormed(List<Message> messages) {
    List<Message> kept = new ArrayList<>();
    int start = 0;
    while (start < messages.size() && messages.get(start).role() != Role.USER) start++;
    while (start < messages.size()) {
      int end = start + 1;
      while (end < messages.size() && messages.get(end).role() != Role.USER) end++;
      List<Message> turn = messages.subList(start, end);
      if (complete(turn)) kept.addAll(turn);
      start = end;
    }
    return kept;
  }

  private static boolean complete(List<Message> turn) {
    Set<String> open = new HashSet<>();
    for (Message m : turn) {
      if (m.role() == Role.TOOL) {
        if (!open.remove(m.toolCallId())) return false;
        continue;
      }
      if (!open.isEmpty()) return false; // results must come right after their calls
      if (m.role() == Role.SYSTEM) return false;
      for (ToolCall c : m.toolCalls()) {
        if (!open.add(c.id())) return false;
      }
    }
    return open.isEmpty();
  }

  static ObjectNode encode(Message m) {
    ObjectNode n = Json.object().put("role", m.role().wire()).put("content", m.content());
    if (m.hasToolCalls()) {
      ArrayNode calls = n.putArray("toolCalls");
      for (ToolCall c : m.toolCalls()) {
        calls.addObject().put("id", c.id()).put("name", c.name()).put("arguments", c.arguments());
      }
    }
    if (m.toolCallId() != null) n.put("toolCallId", m.toolCallId());
    return n;
  }

  static Message decode(JsonNode n) {
    Role role = Role.valueOf(text(n, "role").toUpperCase(Locale.ROOT));
    List<ToolCall> calls = new ArrayList<>();
    JsonNode array = n.path("toolCalls");
    if (!array.isMissingNode() && !array.isArray()) throw new IllegalArgumentException("toolCalls is not a list");
    for (JsonNode c : array) {
      calls.add(new ToolCall(text(c, "id"), text(c, "name"), c.path("arguments").asText(null)));
    }
    JsonNode callId = n.get("toolCallId");
    return new Message(role, text(n, "content"), calls, callId == null || callId.isNull() ? null : callId.asText());
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || !v.isTextual()) throw new IllegalArgumentException("missing " + field);
    return v.asText();
  }

  private static String line(JsonNode n) {
    try {
      return Json.MAPPER.writeValueAsString(n) + "\n";
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e); // a tree of strings always serializes
    }
  }

  private static String reason(Exception e) {
    if (e instanceof JsonProcessingException j) return "invalid JSON (" + j.getOriginalMessage() + ")";
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}
