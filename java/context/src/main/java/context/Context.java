package context;

import core.Component;
import core.Disposable;
import core.Effect;
import core.Fiber;
import core.Signal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a plugin sees: its own fiber, the services it declared, and a way to install more plugins and
 * provide services. Like a core Fiber, a context only reaches DOWN: there is no path to the parent.
 *
 * Every operation creates work in this context's fiber, so it is undone when the context is disposed,
 * in reverse order, exactly once (the core guarantees):
 *  - effect / use : plain core work;
 *  - plugin       : a child plugin, started now or as soon as its services exist;
 *  - provide      : publishes a service to the whole tree; undo = retract it, which first disposes
 *                   every plugin that depends on it;
 *  - on / hook    : listens to an event or answers a hook; undo = unregister, after every running call.
 */
public sealed class Context permits Root {

  final Registry reg;
  final Fiber fiber;
  private final String name;
  private final Map<ServiceKey<?>, Object> deps;

  Context(Registry reg, Fiber fiber, String name, Map<ServiceKey<?>, Object> deps) {
    this.reg = reg;
    this.fiber = fiber;
    this.name = name;
    this.deps = deps;
  }

  /** Creates a new tree. Errors that have no caller (see ErrorHandler) are printed to stderr. */
  public static Root root(String name) {
    return root(name, ErrorHandler.STDERR);
  }

  public static Root root(String name, ErrorHandler errors) {
    Objects.requireNonNull(errors, "errors");
    String n = name == null ? "root" : name;
    return new Root(new Registry(errors), Fiber.root(n), n);
  }

  public String name() {
    return name;
  }

  /** Cancelled as soon as this context starts disposing. */
  public Signal signal() {
    return fiber.signal();
  }

  /**
   * A service this plugin declared in requires(). It stays valid for the whole life of this context:
   * if it is retracted, this context is disposed first.
   */
  public <T> T get(ServiceKey<T> key) {
    Objects.requireNonNull(key, "key");
    Object impl = deps.get(key);
    if (impl == null) {
      throw new IllegalArgumentException("'" + name + "' did not declare service '" + key
          + "' in requires(); only declared services can be used, so their lifetime is tracked");
    }
    return key.type().cast(impl);
  }

  // ---------------- creating work ----------------

  public Disposable effect(Effect effect) {
    return fiber.effect(effect);
  }

  public Disposable effect(String name, Effect effect) {
    return fiber.effect(name, effect);
  }

  /** A plain core component (it receives a Fiber, not a Context: no services). */
  public Disposable use(String name, Component component) {
    return fiber.use(name, component);
  }

  public PluginHandle plugin(Plugin plugin) {
    return plugin(null, plugin);
  }

  /**
   * Installs a plugin under this context. If its services are all present, it is applied now, on this
   * thread, and a failure is thrown here (nothing is left installed). Otherwise it waits; it is applied
   * by whichever thread provides the last missing service, and a failure then goes to the ErrorHandler.
   *
   * Disposing the returned handle uninstalls it for good (it will not restart).
   */
  public PluginHandle plugin(String name, Plugin plugin) {
    Objects.requireNonNull(plugin, "plugin");
    String n = nameOf(name, plugin);
    Host[] made = new Host[1];
    Disposable handle = fiber.use(n, hostFiber -> {
      Host host = new Host(reg, plugin, n, hostFiber);
      hostFiber.signal().onCancel(host::kill);
      hostFiber.effect("watch", signal -> {
        reg.watch(host);
        return () -> reg.unwatch(host);
      });
      host.tryStart(true);
      made[0] = host;
      return null;
    });
    return new PluginHandle(handle, made[0]);
  }

  /**
   * Publishes impl as the service `key` for the whole tree, until this context is disposed or the
   * returned handle is. Plugins waiting for it start now, on this thread.
   *
   * Throws ServiceConflictException if the key is already provided.
   */
  public <T> Disposable provide(ServiceKey<T> key, T impl) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(impl, "impl");
    Registry.Provision p = new Registry.Provision(key, key.cast(impl), name);
    Disposable handle = fiber.effect("provide:" + key.name(), signal -> {
      reg.add(p);
      return () -> reg.retract(p);
    });
    reg.startDependents(key);
    return handle;
  }

  // ---------------- events ----------------

  /**
   * Listens to an event until this context is disposed (or the returned handle is). When the
   * unregistration returns, the listener is not running anywhere and will never be called again.
   */
  public <E> Disposable on(EventKey<E> key, Listener<E> listener) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(listener, "listener");
    return register(key, "on:" + key.name(), listener);
  }

  /** Registers a hook handler, with the same lifetime rules as on(). */
  public <E, R> Disposable hook(HookKey<E, R> key, Hook<E, R> handler) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(handler, "handler");
    return register(key, "hook:" + key.name(), handler);
  }

  /**
   * Calls every listener of the event, in registration order, on this thread. One failing listener does
   * not stop the others; afterwards all failures are thrown together as an EmitException.
   */
  public <E> void emit(EventKey<E> key, E event) {
    Objects.requireNonNull(key, "key");
    reg.bus.emit(key, event);
  }

  /** Asks hook handlers in registration order and returns the first non-null answer. A failure throws at once. */
  public <E, R> Optional<R> bail(HookKey<E, R> key, E event) {
    Objects.requireNonNull(key, "key");
    return reg.bus.bail(key, event);
  }

  /** Asks every hook handler; returns their non-null answers in order. Failures are thrown together after. */
  public <E, R> List<R> collect(HookKey<E, R> key, E event) {
    Objects.requireNonNull(key, "key");
    return reg.bus.collect(key, event);
  }

  private Disposable register(Object key, String slotName, Object fn) {
    return fiber.use(slotName, f -> {
      Bus.Registration r = new Bus.Registration(name + "/" + slotName, f, fn);
      reg.bus.add(key, r);
      // Runs after f is disposed, i.e. after every call still running in it has returned.
      return () -> reg.bus.remove(key, r);
    });
  }

  // ---------------- services ----------------

  /** Whether a service is currently provided. Informational only: it may change right after the call. */
  public boolean isProvided(ServiceKey<?> key) {
    return reg.isProvided(key);
  }

  @Override
  public String toString() {
    return "Context[" + name + "]";
  }

  private static String nameOf(String given, Plugin plugin) {
    if (given != null) return given;
    Object fn = plugin instanceof Plugin.Requiring r ? r.body() : plugin;
    String n = fn.getClass().getSimpleName();
    return n.isEmpty() || n.contains("$$Lambda") ? "plugin" : n;
  }

}
