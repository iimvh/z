package core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

final class SignalTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static void run() {
    Check.section("Component 2: CancelSource (cancellation signal)");

    CancelSource s = new CancelSource("s");
    Check.ok("not cancelled initially", !s.isCancelled() && Check.caught(s::throwIfCancelled) == null);

    List<String> log = new ArrayList<>();
    s.onCancel(() -> log.add("1"));
    s.onCancel(() -> log.add("2"));
    Errors errors = new Errors();
    s.cancel(errors);
    s.cancel(errors);
    Check.eq("each listener runs exactly once, in registration order", List.of("1", "2"), log);
    Check.ok("throwIfCancelled throws once cancelled",
        s.isCancelled() && Check.caught(s::throwIfCancelled) instanceof CancelledException);

    List<String> late = new ArrayList<>();
    s.onCancel(() -> late.add("late"));
    Check.eq("registering after cancellation runs the listener immediately", List.of("late"), late);

    CancelSource u = new CancelSource("u");
    List<String> ulog = new ArrayList<>();
    Disposable reg = u.onCancel(() -> ulog.add("x"));
    reg.dispose();
    reg.dispose();
    u.cancel(new Errors());
    Check.ok("unregistered before cancellation: does not run; unregistering twice is safe",
        ulog.isEmpty() && u.listenerCount() == 0);

    CancelSource e = new CancelSource("e");
    List<String> elog = new ArrayList<>();
    e.onCancel(() -> elog.add("a"));
    e.onCancel(() -> { throw new RuntimeException("boom"); });
    e.onCancel(() -> elog.add("c"));
    Errors ee = new Errors();
    e.cancel(ee);
    Check.ok("a throwing listener does not stop the others; the error is collected",
        elog.equals(List.of("a", "c")) && ee.count() == 1);

    CancelSource p = new CancelSource("p");
    CancelSource c1 = p.child("c1");
    CancelSource c2 = c1.child("c2");
    List<String> clog = new ArrayList<>();
    c2.onCancel(() -> clog.add("c2"));
    p.cancel(new Errors());
    Check.ok("cancelling a parent cancels its descendants", c1.isCancelled() && c2.isCancelled() && clog.equals(List.of("c2")));
    Check.ok("a child created after its parent is cancelled starts cancelled", p.child("late").isCancelled());

    CancelSource q = new CancelSource("q");
    CancelSource qc = q.child("qc");
    Check.eq("has one child", 1, q.childCount());
    qc.detach();
    qc.detach();
    q.cancel(new Errors());
    Check.ok("detach: the parent no longer keeps the child; cancelling the parent does not affect it",
        q.childCount() == 0 && !qc.isCancelled());

    CancelSource deep = new CancelSource("deep");
    CancelSource cur = deep;
    for (int i = 0; i < 200_000; i++) cur = cur.child("d" + i);
    CancelSource deepest = cur;
    Throwable soe = Check.caught(() -> deep.cancel(new Errors()));
    Check.ok("200,000-level deep tree: cancellation does not overflow the stack",
        soe == null && deepest.isCancelled(), soe);

    CancelSource r = new CancelSource("r");
    AtomicInteger rcount = new AtomicInteger();
    List<String> rlog = new ArrayList<>();
    r.onCancel(() -> {
      rcount.incrementAndGet();
      r.cancel(new Errors());                // cancel again from inside a listener
      r.onCancel(() -> rlog.add("inner"));   // register from inside a listener
    });
    Throwable rt = Check.caught(() -> r.cancel(new Errors()));
    Check.ok("re-entrant calls from inside a listener: no deadlock, no second run",
        rt == null && rcount.get() == 1 && rlog.equals(List.of("inner")), rt);

    Check.within("multithreaded: registration racing with cancel - each listener runs exactly once", 30_000,
        SignalTest::raceRegisterVsCancel);
    Check.within("multithreaded: child creation racing with cancel - every child ends up cancelled", 30_000,
        SignalTest::raceChildVsCancel);

    CancelSource m = new CancelSource("m");
    for (int i = 0; i < 1_000_000; i++) m.onCancel(() -> { }).dispose();
    for (int i = 0; i < 200_000; i++) m.child("x").detach();
    Check.ok("1,000,000 register/unregister cycles: nothing accumulates",
        m.listenerCount() == 0 && m.childCount() == 0);
  }

  static void raceRegisterVsCancel() throws Exception {
    for (int round = 0; round < 50; round++) {
      CancelSource s = new CancelSource("race");
      int threads = 4;
      int per = 2_000;
      AtomicIntegerArray runs = new AtomicIntegerArray(threads * per);
      CountDownLatch go = new CountDownLatch(1);
      List<Thread> ts = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int base = t * per;
        Thread th = new Thread(() -> {
          Check.await(go);
          for (int i = 0; i < per; i++) {
            int idx = base + i;
            s.onCancel(() -> runs.incrementAndGet(idx));
          }
        });
        th.start();
        ts.add(th);
      }
      go.countDown();
      s.cancel(new Errors());
      for (Thread th : ts) th.join();
      for (int i = 0; i < runs.length(); i++) {
        if (runs.get(i) != 1) {
          Check.ok("listener " + i + " ran " + runs.get(i) + " times (round " + round + ")", false);
          return;
        }
      }
    }
  }

  static void raceChildVsCancel() throws Exception {
    for (int round = 0; round < 50; round++) {
      CancelSource s = new CancelSource("race");
      List<CancelSource> kids = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch go = new CountDownLatch(1);
      List<Thread> ts = new ArrayList<>();
      for (int t = 0; t < 4; t++) {
        Thread th = new Thread(() -> {
          Check.await(go);
          for (int i = 0; i < 2_000; i++) kids.add(s.child("k"));
        });
        th.start();
        ts.add(th);
      }
      go.countDown();
      s.cancel(new Errors());
      for (Thread th : ts) th.join();
      for (CancelSource k : kids) {
        if (!k.isCancelled()) {
          Check.ok("a child was not cancelled (round " + round + ")", false);
          return;
        }
      }
    }
  }

}
