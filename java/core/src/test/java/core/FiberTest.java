package core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class FiberTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static List<String> newLog() {
    return Collections.synchronizedList(new ArrayList<>());
  }

  static Effect res(List<String> log, String name) {
    return signal -> () -> log.add(name);
  }

  static List<String> copy(List<String> log) {
    synchronized (log) {
      return new ArrayList<>(log);
    }
  }

  static void run() {
    Check.section("Composition: Fiber - basic behaviour");
    basicOrder();
    handleRemovesOneBranch();
    closedRejectsNewWork();
    undoErrorsAreCollected();
    fatalErrorRethrownAfterCleanup();
    componentFailureCleansUp();
    reentrantCreationOrder();          // flaw #4 (P2)
    noUpwardCapability();              // flaw #1 (P3)
    quota();
    noRetention();                     // flaws #5, #6 (P6, P7)
    cancelListenersRunBeforeUndo();

    Check.section("Composition: Fiber - multithreading, re-entrancy, deadlock");
    Check.within("concurrent dispose: the second thread WAITS for the disposal to finish (P1)", 10_000,
        FiberTest::concurrentDisposeWaits);
    Check.within("work still running at dispose: signalled, waited for, disposed in the right order", 10_000,
        FiberTest::pendingRunAtDispose);
    Check.within("dispose called from inside the fiber's own run: deferred, then completes by itself", 10_000,
        FiberTest::selfDisposeInsideRun);
    Check.within("undo calls dispose / its own handle again", 10_000, FiberTest::reentrantInsideUndo);
    Check.within("handle racing with fiber dispose: each item is disposed exactly once", 30_000,
        FiberTest::handleRacesFiberDispose);
    Check.within("cross-thread deadlock between two threads is detected and resolved", 10_000,
        FiberTest::crossThreadDeadlockResolves);
    Check.within("a hung undo is detectable with awaitDisposed + describe (P5)", 10_000,
        FiberTest::hungUndoIsDetectable);
    Check.within("awaitDisposed waiting on itself -> DeadlockException", 10_000, FiberTest::awaitSelfThrows);
    Check.within("the interrupt flag is preserved", 10_000, FiberTest::interruptPreserved);
    Check.within("100,000-level deep tree: no stack overflow (P4)", 120_000, FiberTest::deepTree);
  }

  // ---------------- basics ----------------

  static void basicOrder() {
    List<String> log = newLog();
    Fiber root = Fiber.root("root");
    root.effect("a", res(log, "a"));
    root.use("comp", fiber -> {
      fiber.effect("b", res(log, "b"));
      fiber.use("inner", f2 -> {
        f2.effect("c", res(log, "c"));
        return () -> log.add("inner");
      });
      return () -> log.add("comp");
    });
    root.effect("d", res(log, "d"));
    root.dispose();
    Check.eq("disposed in reverse creation order, children before parents",
        List.of("d", "c", "inner", "b", "comp", "a"), copy(log));
    Check.ok("dispose() returns -> DISPOSED", root.isDisposed() && root.size() == 0);
    Check.ok("a second dispose does nothing", Check.caught(root::dispose) == null && log.size() == 6);
  }

  static void handleRemovesOneBranch() {
    List<String> log = newLog();
    Fiber root = Fiber.root("root");
    root.effect("db", res(log, "db"));
    Disposable server = root.use("server", f -> {
      f.effect("route", res(log, "route"));
      f.use("child", c -> () -> log.add("child"));
      return () -> log.add("server");
    });
    server.dispose();
    server.dispose();
    Check.eq("removes one branch alone, in order, exactly once",
        List.of("child", "route", "server"), copy(log));
    Check.ok("the other branch is untouched", root.isAlive() && root.size() == 1);
    root.dispose();
    Check.eq("the rest is disposed", List.of("child", "route", "server", "db"), copy(log));
  }

  static void closedRejectsNewWork() {
    Fiber f = Fiber.root("f");
    f.dispose();
    AtomicBoolean ran = new AtomicBoolean();
    Throwable t1 = Check.caught(() -> f.effect("x", s -> {
      ran.set(true);
      return null;
    }));
    Throwable t2 = Check.caught(() -> f.use("y", fb -> {
      ran.set(true);
      return null;
    }));
    Check.ok("after dispose: effect()/use() are refused, and run() is not called",
        t1 instanceof FiberClosedException && t2 instanceof FiberClosedException && !ran.get());

    Fiber g = Fiber.root("g");
    AtomicReference<Throwable> inside = new AtomicReference<>();
    g.effect("x", s -> () -> inside.set(Check.caught(() -> g.effect("late", s2 -> null))));
    g.dispose();
    Check.ok("during disposal: creating new work from inside an undo is refused",
        inside.get() instanceof FiberClosedException);

    Check.ok("null arguments -> NullPointerException, state unchanged",
        Check.caught(() -> Fiber.root("n").effect(null)) instanceof NullPointerException);
  }

  static void undoErrorsAreCollected() {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    f.effect("a", res(log, "a"));
    f.effect("b", s -> () -> {
      log.add("b");
      throw new RuntimeException("boom-b");
    });
    f.effect("c", res(log, "c"));
    f.effect("d", s -> () -> {
      log.add("d");
      throw new IllegalStateException("boom-d");
    });
    Throwable t = Check.caught(f::dispose);
    Check.eq("an error does not stop the other undos", List.of("d", "c", "b", "a"), copy(log));
    if (t instanceof DisposeException e) {
      Check.eq("each error carries the name of the work that caused it", List.of("d", "b"),
          e.failures().stream().map(DisposeException.Failure::source).toList());
    } else {
      Check.ok("throws DisposeException", false, t);
    }
    Check.ok("still DISPOSED even though there were errors", f.isDisposed());
  }

  static void fatalErrorRethrownAfterCleanup() {
    List<String> log = newLog();
    StackOverflowError fatal = new StackOverflowError("simulated");
    Fiber f = Fiber.root("f");
    f.effect("a", res(log, "a"));
    f.effect("bad", s -> () -> {
      throw fatal;
    });
    f.effect("c", res(log, "c"));
    Throwable t = Check.caught(f::dispose);
    Check.ok("a fatal error is rethrown unchanged AFTER everything is disposed",
        t == fatal && copy(log).equals(List.of("c", "a")), t);
  }

  static void componentFailureCleansUp() {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    RuntimeException boom = new RuntimeException("apply failed");
    Throwable t = Check.caught(() -> f.use("comp", fiber -> {
      fiber.effect("x", res(log, "x"));
      fiber.effect("y", s -> () -> {
        log.add("y");
        throw new RuntimeException("y-undo");
      });
      throw boom;
    }));
    Check.ok("the error from apply() is rethrown unchanged", t == boom, t);
    Check.eq("work created before the failure is disposed, in order", List.of("y", "x"), copy(log));
    Check.ok("errors from that cleanup are attached, not lost",
        boom.getSuppressed().length == 1 && boom.getSuppressed()[0] instanceof DisposeException d
            && d.failures().get(0).source().equals("y"));
    Check.ok("the failed component slot is no longer in the fiber", f.size() == 0 && f.isAlive());
  }

  static void reentrantCreationOrder() {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    f.effect("old", res(log, "old"));
    f.effect("outer", s -> {
      f.effect("inner", res(log, "inner")); // created re-entrantly on this same fiber
      return () -> log.add("outer");
    });
    f.dispose();
    Check.eq("work created inside run() is disposed BEFORE the work that created it (P2)",
        List.of("inner", "outer", "old"), copy(log));
  }

  static void noUpwardCapability() {
    boolean leak = false;
    for (Method m : Fiber.class.getMethods()) {
      if (m.getReturnType() == Fiber.class && !Modifier.isStatic(m.getModifiers())) leak = true;
    }
    Check.ok("no method returns another fiber (no getParent) (P3)", !leak);
    Check.ok("no public constructor: an orphan fiber cannot be created", Fiber.class.getConstructors().length == 0);
  }

  static void quota() {
    Fiber q = Fiber.root("q", 3);
    for (int i = 0; i < 3; i++) q.effect("e" + i, s -> () -> { });
    AtomicBoolean ran = new AtomicBoolean();
    Throwable t = Check.caught(() -> q.effect("over", s -> {
      ran.set(true);
      return () -> { };
    }));
    Check.ok("over the quota -> refused before run() is called",
        t instanceof IllegalStateException && !(t instanceof FiberClosedException) && !ran.get(), t);

    Fiber q2 = Fiber.root("q2", 2);
    AtomicReference<Throwable> inChild = new AtomicReference<>();
    q2.use("comp", fiber -> {
      fiber.effect("a", s -> () -> { });
      fiber.effect("b", s -> () -> { });
      inChild.set(Check.caught(() -> fiber.effect("c", s -> () -> { })));
      return null;
    });
    Check.ok("child fibers inherit the quota", inChild.get() instanceof IllegalStateException);
  }

  static void noRetention() {
    Fiber m = Fiber.root("m", 10);
    for (int i = 0; i < 1_000_000; i++) m.effect("n", s -> null);
    Check.ok("1,000,000 work items with nothing to undo: not retained, quota not touched",
        m.size() == 0 && m.signal.childCount() == 0);
    for (int i = 0; i < 200_000; i++) m.effect("h", s -> () -> { }).dispose();
    Check.ok("200,000 items removed through their handles: no slots and no child signals left (P6, P7)",
        m.size() == 0 && m.signal.childCount() == 0);
    for (int i = 0; i < 50_000; i++) m.use("c", f -> null).dispose();
    Check.ok("50,000 components removed through their handles: nothing accumulates",
        m.size() == 0 && m.signal.childCount() == 0);
  }

  static void cancelListenersRunBeforeUndo() {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    f.effect("x", s -> {
      s.onCancel(() -> log.add("cancel-x"));
      return () -> log.add("undo-x");
    });
    f.effect("y", s -> {
      s.onCancel(() -> {
        throw new RuntimeException("cancel boom");
      });
      return () -> log.add("undo-y");
    });
    Throwable t = Check.caught(f::dispose);
    Check.eq("cancellation signals fire first, then the undos run",
        List.of("cancel-x", "undo-y", "undo-x"), copy(log));
    Check.ok("a listener error is collected, with its source name",
        t instanceof DisposeException e && e.failures().get(0).source().equals("onCancel:y"), t);
  }

  // ---------------- multithreading ----------------

  static void concurrentDisposeWaits() throws Exception {
    Fiber f = Fiber.root("f");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    AtomicBoolean done = new AtomicBoolean();
    AtomicBoolean secondSawDone = new AtomicBoolean();
    f.effect("slow-undo", s -> () -> {
      started.countDown();
      Check.await(finish);
      done.set(true);
    });
    Thread t1 = new Thread(f::dispose, "disposer-1");
    t1.start();
    started.await();
    Thread t2 = new Thread(() -> {
      f.dispose();
      secondSawDone.set(done.get() && f.isDisposed());
    }, "disposer-2");
    t2.start();
    Thread.sleep(200);
    Check.ok("the second dispose is waiting and has not returned yet", t2.isAlive());
    finish.countDown();
    t1.join();
    t2.join();
    Check.ok("the second dispose returns only after the disposal has finished", secondSawDone.get());
  }

  static void pendingRunAtDispose() throws Exception {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    f.effect("old", res(log, "old"));
    CountDownLatch inRun = new CountDownLatch(1);
    AtomicBoolean sawCancel = new AtomicBoolean();
    AtomicReference<Throwable> runnerError = new AtomicReference<>();
    Thread r = new Thread(() -> runnerError.set(Check.caught(() -> f.effect("slow", s -> {
      inRun.countDown();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!s.isCancelled() && System.nanoTime() < end) Thread.onSpinWait();
      sawCancel.set(s.isCancelled());
      return () -> log.add("slow");
    }))), "runner");
    r.start();
    inRun.await();
    f.effect("newer", res(log, "newer"));
    f.dispose();
    Check.eq("disposed in start order, even for work still running",
        List.of("newer", "slow", "old"), copy(log));
    r.join();
    Check.ok("the running work received the cancellation signal", sawCancel.get());
    Check.ok("the runner is told FiberClosedException (do not use the result)",
        runnerError.get() instanceof FiberClosedException, runnerError.get());
    Check.ok("dispose() returns -> DISPOSED", f.isDisposed());
  }

  static void selfDisposeInsideRun() {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    f.effect("A", res(log, "A"));
    Throwable t = Check.caught(() -> f.effect("B", s -> {
      f.effect("C", res(log, "C"));
      f.dispose(); // B is still running: only C can be disposed, then it suspends
      log.add("after-dispose:" + f.isDisposed());
      return () -> log.add("B");
    }));
    Check.eq("disposal stops at the unfinished work; when it finishes, disposal continues in order",
        List.of("C", "after-dispose:false", "B", "A"), copy(log));
    Check.ok("effect() reports FiberClosedException", t instanceof FiberClosedException, t);
    Check.ok("finally DISPOSED", f.isDisposed());
  }

  static void reentrantInsideUndo() {
    List<String> log = newLog();
    Fiber f = Fiber.root("f");
    Disposable[] handle = new Disposable[1];
    handle[0] = f.effect("x", s -> () -> {
      log.add("x");
      f.dispose();          // re-entrant call: the current thread is the disposer
      handle[0].dispose();  // release the very item being disposed
    });
    f.effect("y", res(log, "y"));
    Throwable t = Check.caught(f::dispose);
    Check.ok("no deadlock, and nothing is disposed twice",
        t == null && copy(log).equals(List.of("y", "x")) && f.isDisposed(), t);
  }

  static void handleRacesFiberDispose() throws Exception {
    for (int round = 0; round < 200; round++) {
      Fiber f = Fiber.root("f");
      int n = 50;
      AtomicInteger[] counts = new AtomicInteger[n];
      List<Disposable> handles = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        AtomicInteger c = counts[i] = new AtomicInteger();
        handles.add(f.effect("e" + i, s -> c::incrementAndGet));
      }
      CyclicBarrier barrier = new CyclicBarrier(2);
      Thread a = new Thread(() -> {
        await(barrier);
        for (Disposable h : handles) h.dispose();
      });
      Thread b = new Thread(() -> {
        await(barrier);
        f.dispose();
      });
      a.start();
      b.start();
      a.join();
      b.join();
      for (int i = 0; i < n; i++) {
        if (counts[i].get() != 1) {
          Check.ok("round " + round + ": item " + i + " was disposed " + counts[i].get() + " times", false);
          return;
        }
      }
      if (!f.isDisposed()) {
        Check.ok("round " + round + ": fiber is not DISPOSED", false);
        return;
      }
    }
    Check.ok("200 rounds x 50 items: each item disposed exactly once", true);
  }

  static void crossThreadDeadlockResolves() throws Exception {
    List<String> log = newLog();
    Fiber f1 = Fiber.root("f1");
    Fiber f2 = Fiber.root("f2");
    f1.effect("f1-base", res(log, "f1-base"));
    f2.effect("f2-base", res(log, "f2-base"));
    CyclicBarrier both = new CyclicBarrier(2);
    AtomicReference<Throwable> e1 = new AtomicReference<>();
    AtomicReference<Throwable> e2 = new AtomicReference<>();
    // Each thread, from INSIDE its own run(), disposes the other thread's fiber: they wait on each other.
    Thread t1 = new Thread(() -> e1.set(Check.caught(() -> f1.effect("t1", s -> {
      await(both);
      f2.dispose();
      return () -> log.add("t1");
    }))), "t1");
    Thread t2 = new Thread(() -> e2.set(Check.caught(() -> f2.effect("t2", s -> {
      await(both);
      f1.dispose();
      return () -> log.add("t2");
    }))), "t2");
    t1.start();
    t2.start();
    t1.join();
    t2.join();
    boolean bothDone = f1.awaitDisposed(5, TimeUnit.SECONDS) && f2.awaitDisposed(5, TimeUnit.SECONDS);
    List<String> got = copy(log);
    Check.ok("no hang; both fibers end up DISPOSED", bothDone, f1.describe() + f2.describe());
    Check.ok("each item is disposed exactly once",
        got.size() == 4 && got.containsAll(List.of("t1", "t2", "f1-base", "f2-base")), got);
    Check.ok("the order within each fiber is still correct",
        got.indexOf("t1") < got.indexOf("f1-base") && got.indexOf("t2") < got.indexOf("f2-base"), got);
    Check.ok("both threads are told FiberClosedException",
        e1.get() instanceof FiberClosedException && e2.get() instanceof FiberClosedException,
        e1.get() + " / " + e2.get());
  }

  static void hungUndoIsDetectable() throws Exception {
    List<String> log = newLog();
    Fiber f = Fiber.root("hang");
    CountDownLatch release = new CountDownLatch(1);
    f.effect("db", res(log, "db"));
    f.effect("stuck", s -> () -> Check.await(release));
    Thread d = new Thread(f::dispose, "disposer");
    d.start();
    boolean early = f.awaitDisposed(300, TimeUnit.MILLISECONDS);
    String desc = f.describe();
    Check.ok("awaitDisposed returns false on timeout (the caller is not blocked forever)", !early);
    Check.ok("describe() shows the hung work and the thread holding it",
        desc.contains("[RELEASING by disposer] stuck") && desc.contains("[ACTIVE] db"), desc);
    release.countDown();
    Check.ok("once unblocked, the disposal finishes",
        f.awaitDisposed(5, TimeUnit.SECONDS) && copy(log).equals(List.of("db")));
    d.join();
  }

  static void awaitSelfThrows() throws Exception {
    Fiber g = Fiber.root("g");
    AtomicReference<Throwable> seen = new AtomicReference<>();
    g.effect("w", s -> () -> seen.set(Check.caught(() -> g.awaitDisposed(1, TimeUnit.SECONDS))));
    g.dispose();
    Check.ok("waiting on itself from inside an undo -> DeadlockException immediately",
        seen.get() instanceof DeadlockException, seen.get());
    Fiber alive = Fiber.root("alive");
    Check.ok("waiting on a fiber nobody is disposing: times out with false",
        !alive.awaitDisposed(50, TimeUnit.MILLISECONDS));
  }

  static void interruptPreserved() throws Exception {
    Fiber f = Fiber.root("f");
    f.effect("a", s -> () -> { });
    Thread.currentThread().interrupt();
    f.dispose();
    Check.ok("dispose() with the interrupt flag set: still disposes, and the flag is kept",
        Thread.interrupted() && f.isDisposed());

    Fiber g = Fiber.root("g");
    CountDownLatch inRun = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    Thread r = new Thread(() -> Check.caught(() -> g.effect("slow", s -> {
      inRun.countDown();
      Check.await(go);
      return () -> { };
    })));
    r.start();
    inRun.await();
    AtomicBoolean flag = new AtomicBoolean();
    AtomicBoolean disposed = new AtomicBoolean();
    Thread d = new Thread(() -> {
      Thread.currentThread().interrupt();
      g.dispose(); // must wait for "slow"; being interrupted does not abandon the disposal
      flag.set(Thread.currentThread().isInterrupted());
      disposed.set(g.isDisposed());
    });
    d.start();
    Thread.sleep(100);
    go.countDown();
    d.join();
    r.join();
    Check.ok("interrupted while waiting: still waits until the disposal finishes, and the flag is restored",
        flag.get() && disposed.get());
  }

  static void deepTree() {
    int depth = 100_000;
    Fiber root = Fiber.root("root");
    int[] last = {Integer.MAX_VALUE};
    boolean[] orderOk = {true};
    int[] count = {0};
    Fiber cur = root;
    for (int i = 0; i < depth; i++) {
      int level = i;
      Fiber[] holder = new Fiber[1];
      cur.use("lvl" + i, fiber -> {
        holder[0] = fiber;
        return () -> {
          if (level >= last[0]) orderOk[0] = false;
          last[0] = level;
          count[0]++;
        };
      });
      cur = holder[0];
    }
    Signal deepest = cur.signal();
    Throwable t = Check.caught(root::dispose);
    Check.ok("disposing 100,000 levels does not overflow the stack", t == null, t);
    Check.ok("the deepest level is disposed first, and every level is reached",
        orderOk[0] && count[0] == depth, count[0]);
    Check.ok("the cancellation signal reaches the deepest level", deepest.isCancelled());
  }

  static void await(CyclicBarrier b) {
    try {
      b.await();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

}
