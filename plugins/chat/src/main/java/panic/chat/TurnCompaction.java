package panic.chat;

import java.util.ArrayList;
import java.util.List;
import panic.llm.Message;
import panic.llm.Role;

/**
 * Summarising a turn's older steps while the turn runs. Clearing tool results ({@link ContextEditor}) cannot bound a
 * long turn: every step still leaves its assistant message, its call's arguments and a placeholder, so the request
 * grows with the number of steps, and what is left for the files the model needs shrinks until it re-reads them in a
 * cycle. Only replacing old steps by a summary of fixed size bounds it. Between turns {@code chat} already does this
 * for whole turns; this is the same inside one.
 *
 * <p>A turn as sent is then: its head (the question, and what a slash prompt added before the first model call),
 * then the progress so far (a model's summary and the tools' {@link ContextEditor#manifest}, exact where the summary
 * may not be), then the newest steps word for word. The conversation keeps every message; only requests change.
 */
final class TurnCompaction {

  private TurnCompaction() {
  }

  /** Tells the model how to summarise the older steps of the task it is still working on. */
  static final String PROMPT = "You are summarising the earlier steps of a task an AI coding assistant is still "
      + "working on, so it can go on from your summary instead of those steps. Write, under these headings: 1. Goal: "
      + "what the user asked, in one or two sentences. 2. Done: what has been changed or decided so far, with exact "
      + "file paths. 3. Findings: facts learned that the next steps need (paths, names, line numbers, commands that "
      + "work or fail, and why), briefly; do not copy file contents. 4. Next: what remains, as concrete next steps. "
      + "Start from the progress summary so far when there is one and keep what still holds. Do not invent anything. "
      + "Answer with the summary only.";

  /** Heading of the progress the summarised steps leave in the request. */
  static final String HEADING = "[Progress so far: your earlier steps of this task were summarised to save context. "
      + "Go on from here; read a file again only if you need its exact text.]";

  /**
   * Where to cut {@code steps} (the summarisable part of a turn, in order) so that the steps kept from there on are
   * estimated at most {@code keepTokens}: always just before an assistant message, so a call is never kept without
   * its results nor a result without its call, and never past the last assistant message, so the newest step is
   * always kept even when it alone is larger. 0 when there is nothing to cut.
   */
  static int cut(List<Message> steps, ContextEditor.Estimator est, int keepTokens) {
    List<Integer> boundaries = new ArrayList<>();
    for (int i = 1; i < steps.size(); i++) {
      if (steps.get(i).role() == Role.ASSISTANT) boundaries.add(i);
    }
    if (boundaries.isEmpty()) return 0;
    long tail = 0;
    int next = steps.size();
    int best = boundaries.get(boundaries.size() - 1);
    for (int b = boundaries.size() - 1; b >= 0; b--) {
      int at = boundaries.get(b);
      for (int i = at; i < next; i++) tail += est.estimate(steps.get(i));
      next = at;
      if (tail > keepTokens) break;
      best = at;
    }
    return best;
  }

  /** The progress text: the heading, the model's summary (when there is one) and the manifest. */
  static String progress(String summary, String manifest) {
    StringBuilder out = new StringBuilder(HEADING);
    if (summary != null && !summary.isBlank()) out.append("\n\n").append(summary.strip());
    if (manifest != null && !manifest.isBlank()) out.append("\n\n").append(manifest.strip());
    return out.toString();
  }

  /**
   * {@code turn} as sent once its messages {@code [head, cut)} were summarised into {@code progress}: the head, the
   * progress, the rest. The progress joins the head's last message when that is the user's, since some providers
   * refuse two user messages in a row. {@code cut} 0 = nothing summarised: {@code turn} itself.
   */
  static List<Message> view(List<Message> turn, int head, int cut, String progress) {
    if (cut <= head) return turn;
    List<Message> out = new ArrayList<>(turn.subList(0, head));
    Message last = out.isEmpty() ? null : out.get(out.size() - 1);
    if (last != null && last.role() == Role.USER) {
      out.set(out.size() - 1, Message.user(last.content() + "\n\n" + progress));
    } else {
      out.add(Message.user(progress));
    }
    out.addAll(turn.subList(cut, turn.size()));
    return out;
  }
}
