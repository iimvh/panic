package panic.trace.store;

import java.util.Comparator;
import java.util.List;
import panic.trace.Span;
import panic.trace.Trace;

/** One complete trace: its root and every span, in the order they started. */
record Recorded(Span root, List<Span> spans) {

  /** Conversation of traces whose root does not name one (spans emitted outside a chat turn). */
  static final String NO_CONVERSATION = "_";

  Recorded {
    spans = spans.stream().sorted(Comparator.comparingLong(Span::startMicros)
        .thenComparing(s -> s.root() ? 0 : 1)).toList();
  }

  String id() {
    return root.traceId();
  }

  String conversationId() {
    String c = root.stringAttr(Trace.CONVERSATION);
    return c == null || c.isBlank() ? NO_CONVERSATION : c;
  }
}
