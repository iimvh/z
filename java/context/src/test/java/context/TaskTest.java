package context;

import core.CancelledException;
import core.DeadlockException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class TaskTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static void run() {
    Check.section("Tasks");
    Check.within("result is delivered through await()", 10_000, TaskTest::result);
    Check.within("dispose interrupts a blocked job and waits for it to return", 10_000, TaskTest::disposeInterrupts);
    Check.within("a plugin's tasks end before the plugin's own cleanup runs", 10_000, TaskTest::pluginOwnsTasks);
    Check.within("retracting a service waits for its dependents' tasks", 10_000, TaskTest::retractWaitsForTasks);
    Check.within("job failure: thrown by await() and reported", 10_000, TaskTest::failure);
    Check.within("a job may dispose its own task; awaiting itself is a DeadlockException", 10_000, TaskTest::selfUse);
    Check.within("await with timeout", 10_000, TaskTest::timeout);
    Check.within("root dispose waits for 2,000 tasks", 60_000, TaskTest::many);
  }

  static void result() throws Exception {
    Root root = Context.root("app");
    Task<Integer> t = root.spawn("sum", s -> 1 + 2);
    Check.eq("await() returns the job's result", 3, t.await());
    Check.ok("isDone()", t.isDone());
    root.dispose();
  }

  static void disposeInterrupts() throws Exception {
    Root root = Context.root("app");
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean finished = new AtomicBoolean();
    Task<String> t = root.spawn("sleeper", s -> {
      started.countDown();
      try {
        Thread.sleep(60_000);
        return "woke";
      } finally {
        Thread.sleep(50);  // still cleaning up after the interrupt...
        finished.set(true);
      }
    });
    Check.await(started);
    Thread.sleep(20);
    t.dispose();
    Check.ok("when dispose() returns, the job has finished", finished.get());
    Throwable e = Check.caught(t::await);
    Check.ok("await() of a cancelled job throws CancelledException", e instanceof CancelledException, e);
    root.dispose();
  }

  static void pluginOwnsTasks() throws Exception {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    CountDownLatch started = new CountDownLatch(1);
    PluginHandle p = root.plugin("worker", ctx -> {
      ctx.spawn("loop", s -> {
        started.countDown();
        while (!s.isCancelled()) Thread.onSpinWait();
        long until = System.nanoTime() + 20_000_000L; // keep "cleaning up" for 20 ms, ignoring the interrupt
        while (System.nanoTime() < until) Thread.onSpinWait();
        log.add("job end");
        return null;
      });
      return () -> log.add("plugin cleanup");
    });
    Check.await(started);
    p.dispose();
    Check.eq("job ends first, then the plugin cleans up", List.of("job end", "plugin cleanup"), ContextTest.copy(log));
    root.dispose();
  }

  static void retractWaitsForTasks() throws Exception {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    CountDownLatch started = new CountDownLatch(1);
    root.plugin("user", Plugin.require(ContextTest.DB).then(ctx -> {
      ContextTest.Db db = ctx.get(ContextTest.DB);
      ctx.spawn("query", s -> {
        started.countDown();
        try {
          Thread.sleep(60_000);
        } catch (InterruptedException e) {
          log.add("query interrupted, last use of " + db.url());
        }
        return null;
      });
      return null;
    }));
    PluginHandle db = root.plugin("db", ctx -> {
      ctx.effect("conn", s -> () -> log.add("db close"));
      ctx.provide(ContextTest.DB, () -> "pg");
      return null;
    });
    Check.await(started);
    db.dispose();
    Check.eq("the dependent's task finishes before the provider closes",
        List.of("query interrupted, last use of pg", "db close"), ContextTest.copy(log));
    root.dispose();
  }

  static void failure() throws Exception {
    List<String> reported = ContextTest.newLog();
    Root root = Context.root("app", (s, e) -> reported.add(s + ": " + e.getMessage()));
    Task<Object> t = root.spawn("bad", s -> {
      throw new IllegalStateException("job failed");
    });
    Throwable e = Check.caught(t::await);
    Check.ok("await() throws the job's own exception", e instanceof IllegalStateException, e);
    Check.eq("and it is reported, even if nobody awaits", List.of("app/bad: job failed"), ContextTest.copy(reported));
    root.dispose();
  }

  static void selfUse() throws Exception {
    Root root = Context.root("app");
    AtomicReference<Task<String>> self = new AtomicReference<>();
    CountDownLatch set = new CountDownLatch(1);
    AtomicReference<Throwable> awaitSelf = new AtomicReference<>();
    Task<String> t = root.spawn("self", s -> {
      Check.await(set);
      awaitSelf.set(Check.caught(() -> self.get().await()));
      self.get().dispose();     // deferred by the core: this job is still running on this thread
      return "done anyway";
    });
    self.set(t);
    set.countDown();
    Throwable e = Check.caught(t::await);
    Check.ok("awaiting its own result throws DeadlockException", awaitSelf.get() instanceof DeadlockException,
        awaitSelf.get());
    Check.ok("disposing its own task does not hang (it completes or is cancelled)",
        e == null || e instanceof CancelledException, e);
    root.dispose();
  }

  static void timeout() throws Exception {
    Root root = Context.root("app");
    CountDownLatch release = new CountDownLatch(1);
    Task<String> t = root.spawn("slow", s -> {
      release.await();
      return "ok";
    });
    Throwable e = Check.caught(() -> t.await(50, TimeUnit.MILLISECONDS));
    Check.ok("await(timeout) times out", e instanceof TimeoutException, e);
    release.countDown();
    Check.eq("and the result arrives later", "ok", t.await());
    root.dispose();
  }

  static void many() throws Exception {
    Root root = Context.root("app");
    AtomicInteger running = new AtomicInteger();
    AtomicInteger seen = new AtomicInteger();
    List<Task<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < 2_000; i++) {
      tasks.add(root.spawn("t" + i, s -> {
        running.incrementAndGet();
        seen.incrementAndGet();
        try {
          Thread.sleep(60_000);   // interrupted on cancel: InterruptedException ends the job
        } finally {
          running.decrementAndGet();
        }
        return null;
      }));
    }
    while (seen.get() < 2_000) Thread.sleep(5);
    root.dispose();
    Check.eq("after root.dispose() no job is still running", 0, running.get());
    int cancelled = 0;
    for (Task<Void> t : tasks) if (Check.caught(t::await) instanceof CancelledException) cancelled++;
    Check.eq("every task reports cancellation", 2_000, cancelled);
  }

}
