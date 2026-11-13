package panic.chat;

import context.ConfigPlugin;
import context.Context;
import context.ServiceKey;
import core.Disposable;
import java.util.ArrayList;
import java.util.List;
import panic.command.Commands;
import panic.llm.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;

/**
 * The chat bot: answers {@link Chat#ASK} with the current LLM, keeping history in {@link Conversations}.
 * Runs only while both services exist. A reply in flight when the plugin is disposed (LLM switched, config
 * changed, app stopping) is cancelled, and the disposal waits for it to return.
 *
 * Command: {@code ask <text...>}, in the session's conversation. It exists only while an LLM is connected.
 */
public final class ChatPlugin implements ConfigPlugin<ChatConfig> {

  @Override
  public List<ServiceKey<?>> requires() {
    return List.of(Llm.KEY, Conversations.KEY);
  }

  @Override
  public Disposable apply(Context ctx, ChatConfig cfg) {
    Llm llm = ctx.get(Llm.KEY);
    Conversations conversations = ctx.get(Conversations.KEY);
    ctx.hook(Chat.ASK, ask -> answer(ctx, cfg, llm, conversations, ask));
    Commands.command(ctx, "ask <text...>", "ask the bot (plain text does the same)", call -> {
      Chat.Ask ask = new Chat.Ask(call.session().id(), call.arg("text"), call.signal(), call.out()::print);
      Chat.Reply r = answer(ctx, cfg, llm, conversations, ask);
      call.out().println();
      StringBuilder meta = new StringBuilder("[").append(r.model());
      if (r.usage().totalTokens() > 0) meta.append(" · ").append(r.usage().totalTokens()).append(" tokens");
      if (r.finishReason() != null && !"stop".equals(r.finishReason())) meta.append(" · ").append(r.finishReason());
      call.out().println(meta.append("]").toString());
    });
    return null;
  }

  private static Chat.Reply answer(Context ctx, ChatConfig cfg, Llm llm, Conversations conversations, Chat.Ask ask) {
    Conversation conversation = conversations.get(ask.conversationId());
    List<Message> messages = new ArrayList<>();
    if (cfg.systemPrompt() != null && !cfg.systemPrompt().isBlank()) {
      messages.add(Message.system(cfg.systemPrompt()));
    }
    messages.addAll(conversation.recent(cfg.maxHistory()));
    Message question = Message.user(ask.text());
    messages.add(question);

    Completion done = llm.stream(ChatRequest.of(messages), CancelToken.any(ask.signal(), ctx.signal()),
        ask.onDelta());
    conversation.append(question, Message.assistant(done.text()));
    Chat.Reply reply = new Chat.Reply(done.text(), done.model(), done.finishReason(), done.usage());
    ctx.emit(Chat.TURN, new Chat.Turn(ask.conversationId(), ask.text(), reply));
    return reply;
  }
}
