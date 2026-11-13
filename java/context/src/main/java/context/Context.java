package context;

import core.Component;
import core.Disposable;
import core.Effect;
import core.Fiber;
import core.Signal;
import java.util.Map;
import java.util.Objects;

/**
 * What a plugin sees: its own fiber, the services it declared, and a way to install more plugins and
 * provide services. Like a core Fiber, a context only reaches DOWN: there is no path to the parent.
 *
 * Every operation creates work in this context's fiber, so it is undone when the context is disposed,
 * in reverse order, exactly once (the core guarantees):
 *  - effect / use : plain core work;
 *  - plugin       : a child plugin, started now or as soon as its services exist;
 *  - provide      : publishes a service to the whole tree; undo = retract it, which first disposes
 *                   every plugin that depends on it.
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
