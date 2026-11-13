package context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Many threads provide, retract, install and uninstall at the same time. Invariants checked:
 *  - every started instance is stopped exactly once by the end;
 *  - an instance of one plugin never overlaps another instance of the same plugin;
 *  - an instance never starts while one of its services is known to be retracted and cleaned up;
 *  - nothing hangs and no error reaches the ErrorHandler.
 */
final class ContextStressTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static final int CONSUMERS = 8;
  static final int ROUNDS = 2_000;

  static void run() {
    Check.section("Context - concurrency stress");
    Check.within("concurrent provide / retract / install: balanced, no overlap, no hang", 120_000,
        ContextStressTest::stress);
  }

  /** A service whose liveness the consumers can check. */
  static final class Live implements ContextTest.Db {
    final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public String url() {
      return "live";
    }
  }

  static void stress() throws Exception {
    List<String> reported = ContextTest.newLog();
    Root root = Context.root("stress", (s, e) -> reported.add(s + ": " + e));
    AtomicInteger started = new AtomicInteger();
    AtomicInteger stopped = new AtomicInteger();
    AtomicInteger overlaps = new AtomicInteger();
    AtomicInteger sawClosed = new AtomicInteger();
    AtomicInteger[] running = new AtomicInteger[CONSUMERS];
    for (int i = 0; i < CONSUMERS; i++) running[i] = new AtomicInteger();

    List<PluginHandle> handles = new ArrayList<>();
    for (int i = 0; i < CONSUMERS; i++) handles.add(root.plugin("c" + i, consumer(i, running, started, stopped,
        overlaps, sawClosed)));

    int threads = 4;
    CyclicBarrier go = new CyclicBarrier(threads);
    List<Thread> ts = new ArrayList<>();
    List<Throwable> sync = Collections.synchronizedList(new ArrayList<>());

    // Thread 0: toggles DB through a provider plugin (its cleanup marks the service closed).
    ts.add(new Thread(() -> loop(go, sync, () -> {
      Live live = new Live();
      PluginHandle p = root.plugin("db", ctx -> {
        ctx.effect("close", s -> () -> live.closed.set(true));
        ctx.provide(ContextTest.DB, live);
        return null;
      });
      p.dispose();
    }), "toggle-db"));
    // Thread 1: toggles CACHE directly.
    ts.add(new Thread(() -> loop(go, sync, () -> root.provide(ContextTest.CACHE, () -> "c").dispose()),
        "toggle-cache"));
    // Threads 2-3: install and uninstall extra consumers.
    for (int t = 2; t < threads; t++) {
      int base = t;
      ts.add(new Thread(() -> loop(go, sync, () -> {
        AtomicInteger r = new AtomicInteger();
        PluginHandle h = root.plugin("extra" + base, Plugin.require(ContextTest.DB).then(ctx -> {
          if (r.incrementAndGet() != 1) overlaps.incrementAndGet();
          started.incrementAndGet();
          return () -> {
            r.decrementAndGet();
            stopped.incrementAndGet();
          };
        }));
        if (ThreadLocalRandom.current().nextBoolean()) Thread.yield();
        h.dispose();
      }), "installer-" + t));
    }
    for (Thread t : ts) t.start();
    for (Thread t : ts) t.join();

    root.dispose();
    Check.ok("no thread failed", sync.isEmpty(), sync);
    Check.ok("no error reached the ErrorHandler", reported.isEmpty(), reported);
    Check.eq("every started instance was stopped exactly once", started.get(), stopped.get());
    Check.ok("instances started at all (the test exercised something)", started.get() > 0, started.get());
    Check.eq("no two instances of one plugin overlapped", 0, overlaps.get());
    Check.eq("no instance started with a service already cleaned up", 0, sawClosed.get());
    Check.ok("root disposed", root.isDisposed());
  }

  static Plugin consumer(int i, AtomicInteger[] running, AtomicInteger started, AtomicInteger stopped,
      AtomicInteger overlaps, AtomicInteger sawClosed) {
    return Plugin.require(ContextTest.DB, ContextTest.CACHE).then(ctx -> {
      ContextTest.Db db = ctx.get(ContextTest.DB);
      if (db instanceof Live live && live.closed.get()) sawClosed.incrementAndGet();
      if (running[i].incrementAndGet() != 1) overlaps.incrementAndGet();
      started.incrementAndGet();
      return () -> {
        running[i].decrementAndGet();
        stopped.incrementAndGet();
      };
    });
  }

  interface Body {
    void run() throws Exception;
  }

  static void loop(CyclicBarrier go, List<Throwable> failures, Body body) {
    try {
      go.await();
      for (int r = 0; r < ROUNDS; r++) body.run();
    } catch (Throwable t) {
      failures.add(t);
    }
  }

}
