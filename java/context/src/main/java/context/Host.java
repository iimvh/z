package context;

import core.Disposable;
import core.Fiber;
import core.FiberClosedException;
import java.util.List;
import java.util.Map;

/**
 * Keeps one installed plugin running exactly while its services exist.
 *
 * The host owns a fiber (the plugin's slot in its parent). Each time the requirements are met, the
 * plugin is applied in a NEW child fiber of that host fiber: an Activation. When a requirement is
 * retracted, that activation is disposed; the next time they are met, a fresh one is created.
 *
 *   host fiber 'server'
 *    ├─ watch           (registered with the Registry; undo = unwatch)
 *    ├─ server#1        RELEASED when 'db' was retracted
 *    └─ server#2        activation fiber
 *        └─ server      the running instance: the plugin's own fiber and context
 *
 * State is guarded by Registry.lock. At most one activation is current. Who runs what:
 *  - start: the thread that saw the requirements become satisfied (plugin() caller or a provider).
 *  - stop:  the thread that retracted a service, or the core teardown when the host itself is disposed.
 * A stop that races with a start still running is safe: the start notices `stopped` and undoes itself,
 * and the core waits for (or defers to) whatever is still running inside the activation fiber.
 */
final class Host {

  final Registry reg;
  final Plugin plugin;
  final String name;
  final List<ServiceKey<?>> requires;
  private final Fiber fiber;

  // guarded by reg.lock
  private boolean alive = true;
  private Activation current;

  Host(Registry reg, Plugin plugin, String name, Fiber fiber) {
    this.reg = reg;
    this.plugin = plugin;
    this.name = name;
    this.requires = List.copyOf(plugin.requires());
    this.fiber = fiber;
  }

  /** One running instance of the plugin. */
  final class Activation {
    final long seq;
    final Map<ServiceKey<?>, Object> deps;
    // guarded by reg.lock
    private Fiber instance;
    private Disposable handle;
    private boolean stopped;

    Activation(long seq, Map<ServiceKey<?>, Object> deps) {
      this.seq = seq;
      this.deps = deps;
    }

    /**
     * Disposes this instance. Called outside the lock, after detachLocked() marked it stopped.
     *
     * Disposing the activation fiber disposes the plugin's slot inside it. If apply() is still running
     * on another thread, that slot is PENDING and the core WAITS for it (deadlock-safe: it defers instead
     * when waiting is impossible). So when stop() returns, the plugin is no longer using its services.
     */
    void stop() {
      Fiber f;
      Disposable h;
      synchronized (reg.lock) {
        f = instance;
        h = handle;
      }
      // f == null: the activation has not been entered yet; it will see `stopped` and do nothing
      Throwable failure = null;
      try {
        if (f != null) f.dispose(); // the plugin's whole branch, then its own cleanup
      } catch (Throwable t) {
        failure = t;
      }
      try {
        if (h != null) h.dispose(); // frees the activation slot in the host fiber (else tryStart() does)
      } catch (Throwable t) {
        if (failure == null) failure = t;
        else if (failure != t) failure.addSuppressed(t);
      }
      if (failure != null) throw sneakyThrow(failure);
    }
  }

  // ---------------- called under reg.lock ----------------

  boolean canStartLocked() {
    return alive && current == null;
  }

  boolean isRunningLocked() {
    return current != null;
  }

  /** Detaches the current activation for stopping. Returns null if none. */
  Activation detachLocked() {
    Activation a = current;
    if (a == null) return null;
    current = null;
    a.stopped = true;
    return a;
  }

  // ---------------- lifecycle ----------------

  /**
   * The host fiber started disposing (or an ancestor did: cancellation reaches the whole subtree at once):
   * never start again. The current activation is KEPT, so a service retracted during this same teardown
   * still stops it first, before the provider cleans up. The core disposes it otherwise.
   */
  void kill() {
    synchronized (reg.lock) {
      alive = false;
    }
  }

  /** Called under reg.lock when the host is unregistered: its fiber is done, drop the reference. */
  void forgetLocked() {
    alive = false;
    current = null;
  }

  boolean isActive() {
    synchronized (reg.lock) {
      return current != null && current.handle != null;
    }
  }

  /**
   * Starts an activation if the plugin is not running and every requirement is present.
   * propagate = true : the caller is plugin(): a failure is thrown to it.
   * propagate = false: the caller is a provider: a failure goes to the ErrorHandler, the provider is unaffected.
   */
  void tryStart(boolean propagate) {
    Activation a;
    synchronized (reg.lock) {
      if (!alive || current != null) return;
      Map<ServiceKey<?>, Object> deps = reg.snapshotLocked(requires);
      if (deps == null) return;
      a = new Activation(reg.nextSeqLocked(), deps);
      current = a;
    }
    Disposable h;
    try {
      // Two levels: the activation fiber is published BEFORE the plugin's slot is reserved inside it,
      // so a concurrent stop() always has a fiber to dispose that contains the running apply().
      h = fiber.use(name + "#" + a.seq, activation -> {
        synchronized (reg.lock) {
          if (a.stopped) return null;   // retracted before we got here: create nothing
          a.instance = activation;
        }
        activation.use(name, f -> plugin.apply(new Context(reg, f, name, a.deps)));
        return null;
      });
    } catch (Throwable t) {
      boolean expected;
      synchronized (reg.lock) {
        if (current == a) current = null;
        // Stopped mid-apply (retracted, or host disposed): the FiberClosedException is the normal outcome.
        expected = (a.stopped || !alive) && t instanceof FiberClosedException;
      }
      if (t instanceof VirtualMachineError e) throw e;
      if (expected) return;
      if (propagate) throw sneakyThrow(t);
      report(t);
      return;
    }
    boolean undo;
    synchronized (reg.lock) {
      a.handle = h;
      undo = a.stopped;
    }
    if (!undo) return;
    try {
      h.dispose();             // stopped while apply() was running: release what it returned
    } catch (Throwable t) {
      if (t instanceof VirtualMachineError e) throw e;
      if (propagate) throw sneakyThrow(t);
      report(t);
    }
  }

  private void report(Throwable t) {
    try {
      reg.errors.report(name, t);
    } catch (Throwable broken) {
      if (broken instanceof VirtualMachineError e) throw e;
      ErrorHandler.STDERR.report(name, t);
      ErrorHandler.STDERR.report(name + " (error handler)", broken);
    }
  }

  @SuppressWarnings("unchecked")
  static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }

}
