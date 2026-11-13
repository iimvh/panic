package panic.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import core.CancelledException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceTest {

  private final Root root = Context.root("t");
  private final List<Span> spans = new ArrayList<>();

  @AfterEach
  void dispose() {
    root.dispose();
  }

  @Test
  void childrenShareTheTraceAndPointAtTheirParent() {
    root.on(Trace.SPAN, spans::add);
    OpenSpan turn = Trace.open(root, null, null, "turn");
    turn.child("llm").attr("step", 1).end();
    turn.attr("steps", 1).end();

    assertEquals(2, spans.size());
    Span llm = spans.get(0);
    Span top = spans.get(1);
    assertTrue(top.root());
    assertEquals(top.traceId(), llm.traceId());
    assertEquals(top.spanId(), llm.parentId());
    assertEquals(1L, llm.attrs().get("step"), "integers are stored as longs");
    assertEquals(Span.Status.OK, top.status());
    assertNull(top.error());
  }

  @Test
  void endingTwiceEmitsOnce() {
    root.on(Trace.SPAN, spans::add);
    OpenSpan s = Trace.open(root, "abc", null, "turn");
    s.end();
    s.fail(new IllegalStateException("late"));
    assertEquals(1, spans.size());
    assertEquals("abc", spans.get(0).traceId());
  }

  @Test
  void cancellationAndErrorsAreTellApart() {
    root.on(Trace.SPAN, spans::add);
    Trace.open(root, null, null, "a").fail(new CancelledException("stopped"));
    Trace.open(root, null, null, "b").fail(new IllegalStateException());
    assertEquals(Span.Status.CANCELLED, spans.get(0).status());
    assertEquals("stopped", spans.get(0).error());
    assertEquals(Span.Status.ERROR, spans.get(1).status());
    assertEquals("IllegalStateException", spans.get(1).error());
  }

  @Test
  void aFailingListenerNeverReachesTheTracedWork() {
    root.on(Trace.SPAN, s -> {
      throw new IllegalStateException("tracer broken");
    });
    root.on(Trace.SPAN, spans::add);
    Trace.open(root, null, null, "turn").end();
    assertEquals(1, spans.size(), "other listeners still run");
  }

  @Test
  void attributesAreLimitedToJsonScalars() {
    assertThrows(IllegalArgumentException.class,
        () -> new Span("t", "s", null, "x", 0, 0, Span.Status.OK, null, Map.of("bad", List.of())));
    root.on(Trace.SPAN, spans::add);
    Trace.open(root, null, null, "turn").attr("bad", new Object()).end();
    assertTrue(spans.get(0).attrs().isEmpty(), "a bad attribute drops the attributes, not the span");
  }
}
