package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import context.Root;
import core.Signal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.Resource;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;

/**
 * The 2026-11-18 failure, replayed: an agent that needs seven files in view at once to write its change, with the
 * sizes of the files it read that night (output chars of its read_file calls), a 3.4K-token system prompt and the
 * user's {@code contextTokens} of 32000. That night every read evicted another of the seven, 795 reads, no code.
 */
class WorkingSetTest {

  /** The read_file output sizes of the cycle in the trace (.panic/traces/af92fbb3...). */
  static final int[] SIZES = {555, 4985, 3085, 5436, 2440, 10323, 3761};

  /** Reads the first file of its working set it does not see in full, one per step; answers once it sees all. */
  static final class Agent implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final int files;

    Agent(int files) {
      this.files = files;
    }

    @Override
    public Provider provider() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String model() {
      return "m";
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      return stream(r, s, d -> { });
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      requests.add(r);
      for (int i = 0; i < files; i++) {
        String full = content(i);
        boolean seen = r.messages().stream().anyMatch(m -> m.role() == Role.TOOL && m.content().equals(full));
        if (!seen) {
          return new Completion("", "m", "tool_calls", Usage.NONE,
              List.of(new ToolCall("c" + requests.size(), "read_file", "{\"path\":\"f" + i + "\"}")));
        }
      }
      return new Completion("done", "m", "stop", Usage.NONE);
    }
  }

  static String content(int i) {
    return ("f" + i + ":").repeat(SIZES[i % SIZES.length] / 3 + 1).substring(0, SIZES[i % SIZES.length]);
  }

  private final Root root = Context.root("t");
  private final Map<String, Conversation> all = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    });
    root.plugin("files", ctx -> {
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("read_file", "Read a file.", null);
        }

        @Override
        public Risk risk() {
          return Risk.READ;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          int i = Integer.parseInt(args.path("path").asText().substring(1));
          String text = content(i);
          return ToolResult.ok(text).about(Resource.view("file:f" + i, "v1", 1, 100), null);
        }
      });
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private Chat.Reply run(Agent agent, int contextTokens) {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, agent);
      return null;
    });
    String system = "s".repeat(13_600); // about 3.4K tokens, as the night's first request
    root.plugin("chat", new ChatPlugin(), new ChatConfig(system, 40, true, 1_000, 0, 0, 0, 0, contextTokens, false));
    return Chat.ask(root, new Chat.Ask("c", "build the blog page", new CancelToken(), d -> { })).orElseThrow();
  }

  @Test
  void aWorkingSetThatFitsIsReadOnceAndKept() {
    Agent agent = new Agent(7);
    Chat.Reply reply = run(agent, 32_000);
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("done", reply.text());
    assertEquals(7, reply.toolCalls(), "each file read once");
    for (ChatRequest r : agent.requests) {
      assertTrue(r.messages().stream().noneMatch(m -> m.role() == Role.TOOL && m.content().startsWith("[")),
          "nothing had to be cleared: 3.4K + 30K chars fit 32K tokens");
    }
  }

  @Test
  void aWorkingSetThatCannotFitStopsAsStalledInsteadOfLooping() {
    // 30K chars of files (about 7.6K tokens) and 3.4K of prompt in 9K: no policy can show all seven at once
    Agent agent = new Agent(7);
    Chat.Reply reply = run(agent, 9_000);
    assertEquals(Chat.STALLED, reply.stop());
    assertTrue(reply.steps() <= 7 + Chat.STALL_STOP_CALLS + 1, "stopped within the stall limit: " + reply.steps());
    List<Message> stored = all.get("c").recent(0);
    assertTrue(stored.stream().anyMatch(m -> m.role() == Role.USER && m.content().startsWith(Chat.STALL_FEEDBACK_PREFIX)),
        "the model was told before the turn stopped");
    assertTrue(reply.text().contains("stalled"), reply.text());
  }
}
