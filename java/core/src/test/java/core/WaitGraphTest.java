package core;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

final class WaitGraphTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static Thread token(String name) {
    return new Thread(() -> { }, name);
  }

  static void run() {
    Check.section("Component 5: WaitGraph (deadlock detection)");
    Thread a = token("A");
    Thread b = token("B");
    Thread c = token("C");
    Thread d = token("D");

    WaitGraph g = new WaitGraph();
    Check.ok("waiting on oneself -> refused", !g.tryEnter(a, a));
    Check.ok("A waits for B: allowed", g.tryEnter(a, b));
    Check.ok("B waits for A: closes a cycle -> refused", !g.tryEnter(b, a));
    Check.ok("B waits for C: allowed", g.tryEnter(b, c));
    Check.ok("C waits for A: closes a 3-thread cycle -> refused", !g.tryEnter(c, a));
    Check.ok("C waits for D: allowed", g.tryEnter(c, d));
    Check.ok("already waiting and waits again -> throws (core bug)",
        Check.caught(() -> g.tryEnter(a, d)) instanceof IllegalStateException);

    g.exit(a);
    Check.ok("A stops waiting: D waiting for A no longer closes a cycle", g.tryEnter(d, a));
    g.exit(b);
    g.exit(c);
    g.exit(d);
    Check.eq("all waits ended: empty graph", 0, g.size());

    Check.within("multithreaded: two threads close the same cycle - exactly one is refused", 30_000, () -> {
      for (int round = 0; round < 2_000; round++) {
        WaitGraph wg = new WaitGraph();
        Thread x = token("X");
        Thread y = token("Y");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger refused = new AtomicInteger();
        Thread t1 = new Thread(() -> {
          await(barrier);
          if (!wg.tryEnter(x, y)) refused.incrementAndGet();
        });
        Thread t2 = new Thread(() -> {
          await(barrier);
          if (!wg.tryEnter(y, x)) refused.incrementAndGet();
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        if (refused.get() != 1) {
          Check.ok("round " + round + ": number of refused threads = " + refused.get(), false);
          return;
        }
      }
    });
  }

  static void await(CyclicBarrier b) {
    try {
      b.await();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}
