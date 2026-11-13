package context;

import core.Disposable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ConfigTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  record Port(int value) {
  }

  /** Restart-only plugin: logs start/stop with its port. */
  static ConfigPlugin<Port> server(List<String> log) {
    return (ctx, cfg) -> {
      log.add("start " + cfg.value());
      return () -> log.add("stop " + cfg.value());
    };
  }

  /** In-place plugin: keeps the current port in a field, reconfigures without restarting. */
  static final class Tunable implements ConfigPlugin<Port> {
    final List<String> log;
    final AtomicInteger port = new AtomicInteger();

    Tunable(List<String> log) {
      this.log = log;
    }

    @Override
    public Disposable apply(Context ctx, Port cfg) {
      port.set(cfg.value());
      log.add("start " + cfg.value());
      return () -> log.add("stop " + port.get());
    }

    @Override
    public boolean reconfigure(Context ctx, Port previous, Port next) {
      if (next.value() < 0) return false;          // negative: ask for a restart
      if (next.value() == 666) throw new IllegalStateException("bad port");
      log.add("retune " + previous.value() + "->" + next.value());
      port.set(next.value());
      return true;
    }
  }

  static void run() {
    Check.section("Config - reload");
    restartOnlyThisBranch();
    equalConfigIsNoop();
    inPlaceReconfigure();
    inPlaceDeclinedRestarts();
    waitingPluginRecordsConfig();
    restartFailureThrows();
    reconfigureFailureStops();
    providerReloadCascades();
    reentrantUpdate();

    Check.section("Config - concurrency");
    Check.within("concurrent updates converge on the latest config, one instance at a time", 60_000,
        ConfigTest::concurrentUpdates);
  }

  static void restartOnlyThisBranch() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    root.plugin("sibling", ctx -> {
      log.add("sibling start");
      return () -> log.add("sibling stop");
    });
    ConfigHandle<Port> h = root.plugin("server", server(log), new Port(80));
    h.update(new Port(81));
    Check.eq("a config change restarts only that plugin, with the new config",
        List.of("sibling start", "start 80", "stop 80", "start 81"), ContextTest.copy(log));
    Check.eq("config() is the new one", new Port(81), h.config());
    Check.ok("still active", h.isActive());
    root.dispose();
  }

  static void equalConfigIsNoop() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    ConfigHandle<Port> h = root.plugin("server", server(log), new Port(80));
    h.update(new Port(80));
    Check.eq("an equal config changes nothing", List.of("start 80"), ContextTest.copy(log));
    root.dispose();
  }

  static void inPlaceReconfigure() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    ConfigHandle<Port> h = root.plugin("tune", new Tunable(log), new Port(1));
    h.update(new Port(2));
    h.update(new Port(3));
    root.dispose();
    Check.eq("reconfigure() handles changes in place, without a restart",
        List.of("start 1", "retune 1->2", "retune 2->3", "stop 3"), ContextTest.copy(log));
  }

  static void inPlaceDeclinedRestarts() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    ConfigHandle<Port> h = root.plugin("tune", new Tunable(log), new Port(1));
    h.update(new Port(-1));
    Check.eq("reconfigure() returning false restarts the instance",
        List.of("start 1", "stop 1", "start -1"), ContextTest.copy(log));
    root.dispose();
  }

  static void waitingPluginRecordsConfig() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    ConfigHandle<Port> h = root.plugin("server", ConfigPlugin.require(ContextTest.DB).then(server(log)), new Port(1));
    h.update(new Port(2));
    h.update(new Port(3));
    Check.eq("while waiting for services, updates are only recorded", List.of(), ContextTest.copy(log));
    root.provide(ContextTest.DB, () -> "d");
    Check.eq("it starts with the latest config", List.of("start 3"), ContextTest.copy(log));
    root.dispose();
  }

  static void restartFailureThrows() {
    Root root = Context.root("app");
    List<String> log = ContextTest.newLog();
    ConfigHandle<Port> h = root.plugin("picky", (ctx, cfg) -> {
      if (cfg.value() == 0) throw new IllegalArgumentException("port 0");
      log.add("start " + cfg.value());
      return null;
    }, new Port(1));
    Throwable t = Check.caught(() -> h.update(new Port(0)));
    Check.ok("a failing restart throws to the update() caller", t instanceof IllegalArgumentException, t);
    Check.ok("and the plugin is not running", !h.isActive());
    h.update(new Port(2));
    Check.ok("a later update starts it again", h.isActive());
    Check.eq("log", List.of("start 1", "start 2"), ContextTest.copy(log));
    root.dispose();
  }

  static void reconfigureFailureStops() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    ConfigHandle<Port> h = root.plugin("tune", new Tunable(log), new Port(1));
    Throwable t = Check.caught(() -> h.update(new Port(666)));
    Check.ok("reconfigure() failure is thrown to the caller", t instanceof IllegalStateException, t);
    Check.ok("and the possibly half-updated instance is disposed", !h.isActive());
    Check.eq("log", List.of("start 1", "stop 1"), ContextTest.copy(log));
    root.dispose();
  }

  static void providerReloadCascades() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    root.plugin("server", ContextTest.serverPlugin(log));
    ConfigHandle<String> db = root.plugin("db", (ctx, url) -> {
      ctx.provide(ContextTest.DB, () -> url);
      return () -> log.add("db close " + url);
    }, "pg://a");
    log.clear();
    db.update("pg://b");
    Check.eq("restarting a provider restarts its dependents with the new service",
        List.of("server stop pg://a", "db close pg://a", "server start pg://b"), ContextTest.copy(log));
    root.dispose();
  }

  static void reentrantUpdate() {
    List<String> log = ContextTest.newLog();
    Root root = Context.root("app");
    AtomicReference<ConfigHandle<Port>> self = new AtomicReference<>();
    ConfigPlugin<Port> p = new ConfigPlugin<>() {
      @Override
      public Disposable apply(Context ctx, Port cfg) {
        log.add("start " + cfg.value());
        return null;
      }

      @Override
      public boolean reconfigure(Context ctx, Port previous, Port next) {
        log.add("retune " + previous.value() + "->" + next.value());
        if (next.value() == 2) self.get().update(new Port(3)); // re-entrant: recorded, applied after
        return true;
      }
    };
    self.set(root.plugin("self", p, new Port(1)));
    self.get().update(new Port(2));
    Check.eq("an update from inside reconfigure() is applied before the outer update returns",
        List.of("start 1", "retune 1->2", "retune 2->3"), ContextTest.copy(log));
    root.dispose();
  }

  static void concurrentUpdates() throws Exception {
    List<String> reported = ContextTest.newLog();
    Root root = Context.root("app", (s, e) -> reported.add(s + ": " + e));
    AtomicInteger running = new AtomicInteger();
    AtomicInteger overlaps = new AtomicInteger();
    AtomicInteger starts = new AtomicInteger();
    AtomicInteger stops = new AtomicInteger();
    AtomicReference<Port> live = new AtomicReference<>();
    ConfigPlugin<Port> p = new ConfigPlugin<>() {
      @Override
      public Disposable apply(Context ctx, Port cfg) {
        if (running.incrementAndGet() != 1) overlaps.incrementAndGet();
        starts.incrementAndGet();
        live.set(cfg);
        return () -> {
          running.decrementAndGet();
          stops.incrementAndGet();
        };
      }

      @Override
      public boolean reconfigure(Context ctx, Port previous, Port next) {
        if ((next.value() & 1) == 0) return false;   // even: restart, odd: in place
        live.set(next);
        return true;
      }
    };
    ConfigHandle<Port> h = root.plugin("p", p, new Port(1));
    int threads = 4;
    CyclicBarrier go = new CyclicBarrier(threads);
    List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> ts = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread th = new Thread(() -> {
        try {
          go.await();
          for (int i = 0; i < 2_000; i++) h.update(new Port(ThreadLocalRandom.current().nextInt(1, 50)));
        } catch (Throwable e) {
          errors.add(e);
        }
      }, "updater-" + t);
      ts.add(th);
      th.start();
    }
    for (Thread th : ts) th.join();
    Check.ok("no updater failed", errors.isEmpty(), errors);
    Check.eq("the running instance has the latest config", h.config(), live.get());
    Check.ok("and is running", h.isActive());
    Check.eq("never two instances at once", 0, overlaps.get());
    root.dispose();
    Check.eq("every start was matched by a stop", starts.get(), stops.get());
    Check.ok("no error reached the ErrorHandler", reported.isEmpty(), reported);
  }

}
