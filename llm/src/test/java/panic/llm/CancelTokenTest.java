package panic.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancelTokenTest {

  @Test
  void anyFollowsEachSourceAndRunsListenersOnce() {
    CancelToken a = new CancelToken();
    CancelToken b = new CancelToken();
    CancelToken both = CancelToken.any(a, b, null);
    AtomicInteger calls = new AtomicInteger();
    both.onCancel(calls::incrementAndGet);
    b.cancel();
    a.cancel();
    both.cancel();
    assertTrue(both.isCancelled());
    assertEquals(1, calls.get());
    both.onCancel(calls::incrementAndGet); // after cancellation: runs at once
    assertEquals(2, calls.get());
  }
}
