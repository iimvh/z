package context;

import core.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class EventsTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static final EventKey<String> MSG = EventKey.of("msg", String.class);
  static final HookKey<String, Integer> ASK = HookKey.of("ask", String.class, Integer.class);

  static void run() {
    Check.section("Events - behaviour");
    emitInOrder();
    listenerFollowsPluginLifetime();
    handleUnregisters();
    failuresGathered();
    bailAndCollect();
    wrongTypesRejected();
    selfUnregisterInsideCall();

    Check.section("Events - concurrency");
    Check.within("unregistering waits for a call running on another thread", 10_000,
        EventsTest::unregisterWaitsForRunningCall);
    Check.within("stress: no call after unregistration returned", 120_000, EventsTest::stress);
  }

  static void emitInOrder() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    root.on(MSG, m -> log.add("a " + m));
    root.plugin("p", ctx -> {
      ctx.on(MSG, m -> log.add("b " + m));
      return null;
    });
    root.emit(MSG, "hi");
    Check.eq("every listener gets the event, in registration order", List.of("a hi", "b hi"), ContextTest.copy(log));
    root.dispose();
    Check.eq("nothing left registered after root dispose", 0, root.reg.bus.count(MSG));
  }

  static void listenerFollowsPluginLifetime() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    root.plugin("l", Plugin.require(ContextTest.DB).then(ctx -> {
      String url = ctx.get(ContextTest.DB).url();
      ctx.on(MSG, m -> log.add(url + " " + m));
      return null;
    }));
    root.emit(MSG, "0");
    Disposable db = root.provide(ContextTest.DB, () -> "one");
    root.emit(MSG, "1");
    db.dispose();
    root.emit(MSG, "2");
    root.provide(ContextTest.DB, () -> "two");
    root.emit(MSG, "3");
    Check.eq("a plugin's listener exists exactly while the plugin runs",
        List.of("one 1", "two 3"), ContextTest.copy(log));
    root.dispose();
  }

  static void handleUnregisters() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    Disposable h = root.on(MSG, log::add);
    root.emit(MSG, "x");
    h.dispose();
    root.emit(MSG, "y");
    Check.eq("the handle unregisters the listener", List.of("x"), ContextTest.copy(log));
    Check.eq("and it is removed from the table", 0, root.reg.bus.count(MSG));
    root.dispose();
  }

  static void failuresGathered() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    root.on(MSG, m -> {
      throw new IllegalStateException("first");
    });
    root.on(MSG, log::add);
    root.on(MSG, m -> {
      throw new IllegalArgumentException("third");
    });
    Throwable t = Check.caught(() -> root.emit(MSG, "go"));
    Check.ok("listener failures are thrown together", t instanceof EmitException e && e.failures().size() == 2, t);
    Check.eq("and do not stop the other listeners", List.of("go"), ContextTest.copy(log));
    root.dispose();
  }

  static void bailAndCollect() {
    Root root = Context.root("app");
    List<String> asked = ContextTest.newLog();
    root.hook(ASK, q -> {
      asked.add("a");
      return null;
    });
    root.hook(ASK, q -> {
      asked.add("b");
      return q.length();
    });
    root.hook(ASK, q -> {
      asked.add("c");
      return 100;
    });
    Optional<Integer> first = root.bail(ASK, "four");
    Check.eq("bail returns the first non-null answer", Optional.of(4), first);
    Check.eq("and stops asking after it", List.of("a", "b"), ContextTest.copy(asked));
    Check.eq("collect returns every non-null answer in order", List.of(4, 100), root.collect(ASK, "four"));
    Check.eq("bail with no handler answers empty", Optional.empty(), Context.root("x").bail(ASK, "q"));
    root.dispose();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static void wrongTypesRejected() {
    Root root = Context.root("app");
    EventKey raw = MSG;
    Check.ok("emitting an event of the wrong type fails",
        Check.caught(() -> root.emit(raw, 42)) instanceof ClassCastException);
    HookKey rawHook = ASK;
    root.hook(rawHook, q -> "not an integer");
    Check.ok("a hook answering the wrong type fails",
        Check.caught(() -> root.bail(ASK, "q")) instanceof ClassCastException);
    root.dispose();
  }

  static void selfUnregisterInsideCall() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    AtomicReference<Disposable> self = new AtomicReference<>();
    self.set(root.on(MSG, m -> {
      log.add(m);
      self.get().dispose();       // the core defers: this call is still running on this thread
      log.add("after dispose " + m);
    }));
    root.emit(MSG, "1");
    root.emit(MSG, "2");
    Check.eq("a listener can unregister itself during its own call; it is not called again",
        List.of("1", "after dispose 1"), ContextTest.copy(log));
    Check.eq("and it is removed once the call returned", 0, root.reg.bus.count(MSG));
    root.dispose();
  }

  static void unregisterWaitsForRunningCall() throws Exception {
    Root root = Context.root("app");
    CountDownLatch inCall = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean running = new AtomicBoolean();
    Disposable h = root.on(MSG, m -> {
      running.set(true);
      inCall.countDown();
      Check.await(release);
      Check.sleep(50);
      running.set(false);
    });
    Thread emitter = new Thread(() -> root.emit(MSG, "slow"), "emitter");
    emitter.start();
    Check.await(inCall);
    Thread releaser = new Thread(() -> {
      Check.sleep(100);
      release.countDown();
    });
    releaser.start();
    h.dispose();
    Check.ok("when dispose() returns, the call on the other thread has finished", !running.get());
    emitter.join();
    root.dispose();
  }

  static void stress() throws Exception {
    Root root = Context.root("stress");
    int listeners = 4;
    int rounds = 2_000;
    AtomicInteger lateCalls = new AtomicInteger();
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean stop = new AtomicBoolean();
    List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

    List<Thread> emitters = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Thread t = new Thread(() -> {
        try {
          while (!stop.get()) root.emit(MSG, "e");
        } catch (Throwable e) {
          errors.add(e);
        }
      }, "emitter-" + i);
      emitters.add(t);
      t.start();
    }
    List<Thread> togglers = new ArrayList<>();
    for (int i = 0; i < listeners; i++) {
      Thread t = new Thread(() -> {
        try {
          for (int r = 0; r < rounds; r++) {
            AtomicBoolean gone = new AtomicBoolean();
            PluginHandle p = root.plugin("l", ctx -> {
              ctx.on(MSG, m -> {
                if (gone.get()) lateCalls.incrementAndGet();
                calls.incrementAndGet();
              });
              return null;
            });
            p.dispose();
            gone.set(true);
          }
        } catch (Throwable e) {
          errors.add(e);
        }
      }, "toggler-" + i);
      togglers.add(t);
      t.start();
    }
    for (Thread t : togglers) t.join();
    stop.set(true);
    for (Thread t : emitters) t.join();
    root.dispose();
    Check.ok("no thread failed", errors.isEmpty(), errors);
    Check.ok("listeners were really called (" + calls.get() + " calls)", calls.get() > 0);
    Check.eq("no listener was called after its unregistration returned", 0, lateCalls.get());
    Check.eq("table is empty at the end", 0, root.reg.bus.count(MSG));
  }

}
