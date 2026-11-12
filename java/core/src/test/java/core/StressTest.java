package core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Randomized multithreaded test. Invariants checked after every round:
 *  - no hang;
 *  - the root ends up DISPOSED;
 *  - every work item that returned an undo is disposed EXACTLY ONCE;
 *  - no error other than FiberClosedException;
 *  - in normal mode (nobody calls dispose from inside run): root.dispose() returns with DISPOSED already set.
 * Chaos mode: run() also calls dispose / releases handles at random (including the fiber that contains
 * the running work), which forces deferrals, takeovers and cross-thread deadlock avoidance.
 */
final class StressTest {

  static final int ROUNDS = 40;
  static final int WORKERS = 6;
  static final int OPS = 1000;

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  // Statistics, to prove the test really generates load (and does not pass by doing nothing).
  static final AtomicInteger totalCreated = new AtomicInteger();
  static final AtomicInteger earlyReturns = new AtomicInteger();
  static final AtomicInteger deferrals = new AtomicInteger(); // dispose() that had to defer (called from inside run)

  static void run() {
    Check.section("Composition: randomized multithreaded test");
    for (boolean chaos : new boolean[] {false, true}) {
      String mode = chaos ? "chaos" : "normal";
      String[] firstError = {null};
      int[] failedRound = {-1};
      totalCreated.set(0);
      earlyReturns.set(0);
      deferrals.set(0);
      Check.within(ROUNDS + " rounds in " + mode + " mode", 300_000, () -> {
        for (int r = 0; r < ROUNDS && firstError[0] == null; r++) {
          String err = round(1000L * r + (chaos ? 7 : 0), chaos);
          if (err != null) {
            firstError[0] = err;
            failedRound[0] = r;
          }
        }
      });
      Check.ok(ROUNDS + " rounds x " + WORKERS + " threads x " + OPS + " operations, " + mode + " mode"
              + " (created and disposed " + totalCreated.get() + " items; root.dispose() had to defer in "
              + earlyReturns.get() + " rounds; dispose() inside run deferred " + deferrals.get() + " times)",
          firstError[0] == null, "round " + failedRound[0] + ": " + firstError[0]);
      Check.ok(mode + " mode: real load (> 50,000 items)", totalCreated.get() > 50_000, totalCreated.get());
      if (chaos) {
        Check.ok("chaos mode: the deferral / takeover paths were really exercised", deferrals.get() > 100,
            deferrals.get());
      }
    }
  }

  static final class World {
    final Fiber root = Fiber.root("stress");
    final List<Fiber> fibers = Collections.synchronizedList(new ArrayList<>(List.of(root)));
    final List<Disposable> handles = Collections.synchronizedList(new ArrayList<>());
    final Set<Integer> created = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<Integer, AtomicInteger> undone = new ConcurrentHashMap<>();
    final AtomicInteger ids = new AtomicInteger();
    final Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
    final AtomicInteger opsDone = new AtomicInteger();
    final boolean chaos;

    World(boolean chaos) {
      this.chaos = chaos;
    }

    Disposable undoFor(int id) {
      return () -> undone.computeIfAbsent(id, x -> new AtomicInteger()).incrementAndGet();
    }

    // Prefers live fibers; dead fibers are removed from the list (the root is always at index 0).
    Fiber pickFiber(Random rnd) {
      synchronized (fibers) {
        for (int tries = 0; tries < 8 && fibers.size() > 1; tries++) {
          int i = 1 + rnd.nextInt(fibers.size() - 1);
          Fiber f = fibers.get(i);
          if (f.isAlive()) return f;
          fibers.set(i, fibers.get(fibers.size() - 1));
          fibers.remove(fibers.size() - 1);
        }
        return root;
      }
    }

    // Workers never dispose the root: only the main thread does that.
    Fiber pickNonRoot(Random rnd) {
      synchronized (fibers) {
        return fibers.size() < 2 ? null : fibers.get(1 + rnd.nextInt(fibers.size() - 1));
      }
    }

    Disposable pickHandle(Random rnd) {
      synchronized (handles) {
        return handles.isEmpty() ? null : handles.get(rnd.nextInt(handles.size()));
      }
    }
  }

  static String round(long seed, boolean chaos) throws Exception {
    World w = new World(chaos);
    Random main = new Random(seed);
    CountDownLatch go = new CountDownLatch(1);
    Thread[] ts = new Thread[WORKERS];
    for (int i = 0; i < WORKERS; i++) {
      long s = seed * 31 + i;
      ts[i] = new Thread(() -> {
        Random rnd = new Random(s);
        Check.await(go);
        for (int k = 0; k < OPS; k++) {
          safeOp(w, rnd, 0, null);
          w.opsDone.incrementAndGet();
        }
      }, "worker-" + i);
      ts[i].start();
    }
    go.countDown();
    // Dispose the root at a random moment: between 10% and 100% of the total operations.
    int threshold = WORKERS * OPS * (10 + main.nextInt(91)) / 100;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (w.opsDone.get() < threshold && System.nanoTime() < deadline) Thread.onSpinWait();
    w.root.dispose();
    boolean disposedRightAfter = w.root.isDisposed();
    if (!disposedRightAfter) earlyReturns.incrementAndGet();
    for (Thread t : ts) {
      t.join(20_000);
      if (t.isAlive()) return "thread " + t.getName() + " is hung\n" + w.root.describe();
    }
    if (!w.root.awaitDisposed(10, TimeUnit.SECONDS)) return "root did not reach DISPOSED\n" + w.root.describe();
    if (!chaos && !disposedRightAfter) {
      return "dispose() returned before DISPOSED although nothing should have deferred it";
    }
    if (!w.unexpected.isEmpty()) {
      Throwable t = w.unexpected.peek();
      t.printStackTrace(System.out);
      return "unexpected error: " + t;
    }
    for (Integer id : w.created) {
      AtomicInteger c = w.undone.get(id);
      int n = c == null ? 0 : c.get();
      if (n != 1) return "item " + id + " was disposed " + n + " times";
    }
    for (Integer id : w.undone.keySet()) {
      if (!w.created.contains(id)) return "disposed an item that never existed: " + id;
    }
    totalCreated.addAndGet(w.created.size());
    return null;
  }

  static void safeOp(World w, Random rnd, int depth, Fiber current) {
    try {
      op(w, rnd, depth, current);
    } catch (FiberClosedException expected) {
      // the fiber is closed: this is the correct behaviour
    } catch (Throwable t) {
      w.unexpected.add(t);
    }
  }

  // current: the fiber that contains the running run()/apply() (null at the outermost level).
  static void op(World w, Random rnd, int depth, Fiber current) {
    boolean mayDispose = depth == 0 || w.chaos;
    int k = rnd.nextInt(mayDispose ? 11 : 6);
    Fiber f = w.pickFiber(rnd);
    if (k < 4) {
      int id = w.ids.incrementAndGet();
      w.handles.add(f.effect("e" + id, signal -> {
        if (depth < 2 && rnd.nextInt(3) == 0) safeOp(w, rnd, depth + 1, f);
        w.created.add(id); // recorded just before the undo is returned
        return w.undoFor(id);
      }));
    } else if (k < 6) {
      int id = w.ids.incrementAndGet();
      w.handles.add(f.use("c" + id, fiber -> {
        w.fibers.add(fiber);
        int n = rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
          int cid = w.ids.incrementAndGet();
          try {
            fiber.effect("e" + cid, signal -> {
              w.created.add(cid);
              return w.undoFor(cid);
            });
          } catch (FiberClosedException expected) {
            // the child fiber may be disposed by another thread at this moment
          }
        }
        if (depth < 2 && rnd.nextInt(3) == 0) safeOp(w, rnd, depth + 1, fiber);
        w.created.add(id);
        return w.undoFor(id);
      }));
    } else if (k < 8) {
      Disposable h = w.pickHandle(rnd);
      if (h != null) h.dispose();
    } else {
      // k == 10 (only when disposal is allowed): dispose the fiber that contains this very run() - it must defer.
      Fiber victim = (k == 10 && current != null && current != w.root) ? current : w.pickNonRoot(rnd);
      if (victim == null) return;
      victim.dispose();
      if (depth > 0 && !victim.isDisposed()) deferrals.incrementAndGet();
    }
  }

}
