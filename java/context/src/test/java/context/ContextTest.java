package context;

import core.Disposable;
import core.DisposeException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class ContextTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  interface Db {
    String url();
  }

  interface Cache {
    String tag();
  }

  static final ServiceKey<Db> DB = ServiceKey.of("db", Db.class);
  static final ServiceKey<Cache> CACHE = ServiceKey.of("cache", Cache.class);

  static List<String> newLog() {
    return Collections.synchronizedList(new ArrayList<>());
  }

  static List<String> copy(List<String> log) {
    synchronized (log) {
      return new ArrayList<>(log);
    }
  }

  /** A provider plugin: provides DB with the given url, logs open/close around it. */
  static Plugin dbPlugin(List<String> log, String url) {
    return ctx -> {
      log.add("db open " + url);
      ctx.effect("conn", s -> () -> log.add("db close " + url));
      ctx.provide(DB, () -> url);
      return null;
    };
  }

  /** A consumer plugin: requires DB, logs start/stop with the url it was given. */
  static Plugin serverPlugin(List<String> log) {
    return Plugin.require(DB).then(ctx -> {
      String url = ctx.get(DB).url();
      log.add("server start " + url);
      return () -> log.add("server stop " + url);
    });
  }

  static void run() {
    Check.section("Context - plugins without services");
    plainPluginOrder();
    pluginHandleUninstalls();

    Check.section("Context - services");
    consumerWaitsForProvider();
    consumerStopsBeforeServiceCleanup();
    consumerRestartsOnReprovide();
    consumerBeforeProviderInTree();
    providerBeforeConsumerInTree();
    multipleRequirements();
    chainCascade();
    undeclaredServiceRejected();
    conflictingProvider();
    wrongTypeRejected();
    providerHandleRetractsEarly();

    Check.section("Context - failures");
    immediateFailurePropagates();
    deferredFailureReported();
    retractErrorsCollected();
    describeShowsWaiting();
  }

  // ---------------- plugins without services ----------------

  static void plainPluginOrder() {
    List<String> log = newLog();
    Root root = Context.root("app");
    root.plugin("a", ctx -> {
      log.add("a start");
      ctx.plugin("b", c -> {
        log.add("b start");
        return () -> log.add("b stop");
      });
      ctx.effect("e", s -> () -> log.add("a.e undo"));
      return () -> log.add("a stop");
    });
    root.dispose();
    Check.eq("plugin without requirements runs at once; teardown in reverse order",
        List.of("a start", "b start", "a.e undo", "b stop", "a stop"), copy(log));
    Check.ok("root is disposed", root.isDisposed());
  }

  static void pluginHandleUninstalls() {
    List<String> log = newLog();
    Root root = Context.root("app");
    PluginHandle h = root.plugin("server", serverPlugin(log));
    Check.ok("waiting plugin is not active", !h.isActive());
    h.dispose();
    root.plugin("db", dbPlugin(log, "x"));
    Check.eq("an uninstalled plugin never starts", List.of("db open x"), copy(log));
    root.dispose();
  }

  // ---------------- services ----------------

  static void consumerWaitsForProvider() {
    List<String> log = newLog();
    Root root = Context.root("app");
    PluginHandle server = root.plugin("server", serverPlugin(log));
    Check.eq("consumer does not run before its service exists", List.of(), copy(log));
    root.plugin("db", dbPlugin(log, "pg://1"));
    Check.eq("consumer starts as soon as the service is provided", List.of("db open pg://1", "server start pg://1"),
        copy(log));
    Check.ok("consumer handle is active", server.isActive());
    root.dispose();
  }

  static void consumerStopsBeforeServiceCleanup() {
    List<String> log = newLog();
    Root root = Context.root("app");
    root.plugin("server", serverPlugin(log));
    PluginHandle db = root.plugin("db", dbPlugin(log, "pg"));
    log.clear();
    db.dispose();
    Check.eq("retracting a service stops its consumers BEFORE the provider cleans up",
        List.of("server stop pg", "db close pg"), copy(log));
    root.dispose();
  }

  static void consumerRestartsOnReprovide() {
    List<String> log = newLog();
    Root root = Context.root("app");
    PluginHandle server = root.plugin("server", serverPlugin(log));
    root.plugin("db", dbPlugin(log, "one")).dispose();
    Check.ok("consumer inactive after its service is gone", !server.isActive());
    root.plugin("db", dbPlugin(log, "two"));
    Check.ok("consumer active again", server.isActive());
    Check.eq("consumer is applied again, in a fresh context, with the new service",
        List.of("db open one", "server start one", "server stop one", "db close one",
            "db open two", "server start two"), copy(log));
    root.dispose();
  }

  static void consumerBeforeProviderInTree() {
    List<String> log = newLog();
    Root root = Context.root("app");
    root.plugin("server", serverPlugin(log));
    root.plugin("db", dbPlugin(log, "pg"));
    log.clear();
    root.dispose();
    Check.eq("root dispose (consumer installed first): consumer stops before the db closes",
        List.of("server stop pg", "db close pg"), copy(log));
  }

  static void providerBeforeConsumerInTree() {
    List<String> log = newLog();
    Root root = Context.root("app");
    root.plugin("db", dbPlugin(log, "pg"));
    root.plugin("server", serverPlugin(log));
    log.clear();
    root.dispose();
    Check.eq("root dispose (provider installed first): reverse order, consumer first",
        List.of("server stop pg", "db close pg"), copy(log));
  }

  static void multipleRequirements() {
    List<String> log = newLog();
    Root root = Context.root("app");
    PluginHandle both = root.plugin("both", Plugin.require(DB, CACHE).then(ctx -> {
      log.add("both start " + ctx.get(DB).url() + "/" + ctx.get(CACHE).tag());
      return () -> log.add("both stop");
    }));
    root.provide(DB, () -> "d");
    Check.ok("one of two requirements: still waiting", !both.isActive());
    Disposable cache = root.provide(CACHE, () -> "c");
    Check.ok("both requirements: running", both.isActive());
    cache.dispose();
    Check.ok("one requirement retracted: stopped", !both.isActive());
    Check.eq("log", List.of("both start d/c", "both stop"), copy(log));
    root.dispose();
  }

  static void chainCascade() {
    // a provides DB; b requires DB and provides CACHE; c requires CACHE.
    List<String> log = newLog();
    Root root = Context.root("app");
    root.plugin("c", Plugin.require(CACHE).then(ctx -> {
      log.add("c start");
      return () -> log.add("c stop");
    }));
    root.plugin("b", Plugin.require(DB).then(ctx -> {
      log.add("b start");
      ctx.provide(CACHE, () -> "from-b");
      return () -> log.add("b stop");
    }));
    PluginHandle a = root.plugin("a", ctx -> {
      ctx.provide(DB, () -> "a");
      return () -> log.add("a stop");
    });
    Check.eq("chain starts in dependency order", List.of("b start", "c start"), copy(log));
    log.clear();
    a.dispose();
    Check.eq("retracting the root of a chain cascades: deepest dependent first",
        List.of("c stop", "b stop", "a stop"), copy(log));
    root.dispose();
  }

  static void undeclaredServiceRejected() {
    Root root = Context.root("app");
    root.provide(DB, () -> "d");
    AtomicReference<Throwable> seen = new AtomicReference<>();
    root.plugin("sneaky", ctx -> {
      seen.set(Check.caught(() -> ctx.get(DB)));
      return null;
    });
    Check.ok("get() of a service not in requires() is rejected (lifetime would be untracked)",
        seen.get() instanceof IllegalArgumentException, seen.get());
    Check.ok("root cannot get() services either", Check.caught(() -> root.get(DB)) instanceof IllegalArgumentException);
    root.dispose();
  }

  static void conflictingProvider() {
    Root root = Context.root("app");
    Disposable first = root.provide(DB, () -> "1");
    Throwable t = Check.caught(() -> root.provide(DB, () -> "2"));
    Check.ok("second provider of the same key is rejected", t instanceof ServiceConflictException, t);
    first.dispose();
    Check.ok("after the first is retracted, another can provide",
        Check.caught(() -> root.provide(DB, () -> "3")) == null);
    root.dispose();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static void wrongTypeRejected() {
    Root root = Context.root("app");
    ServiceKey raw = DB;
    Throwable t = Check.caught(() -> root.provide(raw, "not a db"));
    Check.ok("providing an object of the wrong type fails at provide()", t instanceof ClassCastException, t);
    Check.ok("and nothing was provided", !root.isProvided(DB));
    root.dispose();
  }

  static void providerHandleRetractsEarly() {
    List<String> log = newLog();
    Root root = Context.root("app");
    root.plugin("server", serverPlugin(log));
    Disposable p = root.provide(DB, () -> "h");
    p.dispose();
    Check.eq("the provide() handle retracts the service on its own",
        List.of("server start h", "server stop h"), copy(log));
    Check.ok("service gone", !root.isProvided(DB));
    root.dispose();
  }

  // ---------------- failures ----------------

  static void immediateFailurePropagates() {
    List<String> log = newLog();
    Root root = Context.root("app");
    RuntimeException boom = new RuntimeException("boom");
    Throwable t = Check.caught(() -> root.plugin("bad", ctx -> {
      ctx.effect("opened", s -> () -> log.add("opened undone"));
      ctx.provide(CACHE, () -> "half");
      throw boom;
    }));
    Check.ok("failure while applying now is thrown to the plugin() caller", t == boom, t);
    Check.eq("and what it created is undone", List.of("opened undone"), copy(log));
    Check.ok("including the service it provided", !root.isProvided(CACHE));
    root.dispose();
  }

  static void deferredFailureReported() {
    List<String> reported = newLog();
    Root root = Context.root("app", (source, error) -> reported.add(source + ": " + error.getMessage()));
    AtomicInteger attempts = new AtomicInteger();
    PluginHandle bad = root.plugin("bad", Plugin.require(DB).then(ctx -> {
      if (attempts.incrementAndGet() == 1) throw new IllegalStateException("first try fails");
      return null;
    }));
    Disposable p = root.provide(DB, () -> "d");
    Check.eq("failure on a provider's thread goes to the ErrorHandler", List.of("bad: first try fails"), copy(reported));
    Check.ok("the provider is unaffected", root.isProvided(DB));
    Check.ok("the failed plugin is not active", !bad.isActive());
    p.dispose();
    root.provide(DB, () -> "d2");
    Check.ok("it is tried again when its services change", bad.isActive());
    root.dispose();
  }

  static void retractErrorsCollected() {
    Root root = Context.root("app");
    root.plugin("x", Plugin.require(DB).then(ctx -> () -> {
      throw new IllegalStateException("x stop fails");
    }));
    List<String> log = newLog();
    root.plugin("y", Plugin.require(DB).then(ctx -> () -> log.add("y stop")));
    Disposable p = root.provide(DB, () -> "d");
    Throwable t = Check.caught(p::dispose);
    Check.ok("a failing dependent's cleanup is reported to whoever retracted", t instanceof DisposeException, t);
    Check.eq("and does not stop the other dependents", List.of("y stop"), copy(log));
    Check.ok("the service is gone anyway", !root.isProvided(DB));
    root.dispose();
  }

  static void describeShowsWaiting() {
    Root root = Context.root("app");
    root.plugin("server", serverPlugin(newLog()));
    String d = root.describe();
    Check.ok("describe() lists plugins waiting for a service", d.contains("'server' missing [db:Db]"), d);
    root.dispose();
  }

}
