package context;

import core.Disposable;
import core.Fiber;
import core.FiberClosedException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps one installed plugin running exactly while its services exist, with its latest config.
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
 *  - stop:  the thread that retracted a service, the updater (restart), or the core teardown when the
 *           host itself is disposed.
 *  - update: ONE thread at a time per host (the "updater"); others only record the new config.
 * A stop that races with a start still running is safe: the start notices `stopped` and undoes itself,
 * and the core waits for (or defers to) whatever is still running inside the activation fiber.
 */
final class Host {

  /** What the host runs: a plugin with a config (null for plain plugins). */
  interface Body {
    Disposable apply(Context ctx, Object config);

    /** In-place reconfiguration; false = not supported, restart instead. */
    boolean reconfigure(Context ctx, Object previous, Object next);
  }

  final Registry reg;
  final String name;
  final List<ServiceKey<?>> requires;
  private final Body body;
  private final Fiber fiber;

  // guarded by reg.lock
  private boolean alive = true;
  private Activation current;
  private Object config;
  private boolean updating;

  Host(Registry reg, String name, List<ServiceKey<?>> requires, Body body, Object config, Fiber fiber) {
    this.reg = reg;
    this.name = name;
    this.requires = List.copyOf(requires);
    this.body = body;
    this.config = config;
    this.fiber = fiber;
  }

  /** One running instance of the plugin. */
  final class Activation {
    final long seq;
    final Map<ServiceKey<?>, Object> deps;
    // guarded by reg.lock
    private Object config;
    private Fiber instance;
    private Context ctx;
    private Disposable handle;
    private boolean stopped;

    Activation(long seq, Map<ServiceKey<?>, Object> deps, Object config) {
      this.seq = seq;
      this.deps = deps;
      this.config = config;
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

  /** Called under reg.lock when the host is unregistered: its fiber is done, drop the reference. */
  void forgetLocked() {
    alive = false;
    current = null;
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

  boolean isActive() {
    synchronized (reg.lock) {
      return current != null && current.handle != null;
    }
  }

  Object config() {
    synchronized (reg.lock) {
      return config;
    }
  }

  /**
   * Starts an activation if the plugin is not running and every requirement is present. It always uses
   * the LATEST config.
   * propagate = true : the caller is plugin() or update(): a failure is thrown to it.
   * propagate = false: the caller is a provider: a failure goes to the ErrorHandler, the provider is unaffected.
   */
  void tryStart(boolean propagate) {
    Activation a;
    synchronized (reg.lock) {
      if (!alive || current != null) return;
      Map<ServiceKey<?>, Object> deps = reg.snapshotLocked(requires);
      if (deps == null) return;
      a = new Activation(reg.nextSeqLocked(), deps, config);
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
        activation.use(name, f -> {
          Context ctx = new Context(reg, f, name, a.deps);
          synchronized (reg.lock) {
            a.ctx = ctx;
          }
          return body.apply(ctx, a.config);
        });
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

  // ---------------- reconfiguration ----------------

  /**
   * Records a new config and brings the running instance to it: in place if the plugin's reconfigure()
   * accepts it, otherwise by restarting it (dispose the instance, apply again with the new config).
   *
   * Only one thread at a time runs this loop for a host. A concurrent (or re-entrant) update() only
   * records its config; the running updater picks it up before it returns. Not running (waiting for
   * services): only recorded; the next start uses it.
   */
  void update(Object next) {
    synchronized (reg.lock) {
      if (Objects.equals(config, next)) return;
      config = next;
      if (updating) return;
      updating = true;
    }
    boolean released = false; // true once `updating` was cleared under the lock: never clear it again
    boolean triedStart = false;
    try {
      for (;;) {
        Activation a;
        Object target;
        boolean inPlace;
        synchronized (reg.lock) {
          target = config;
          a = current;
          boolean done = a == null ? triedStart || !canStartLocked() : Objects.equals(a.config, target);
          if (done) {
            updating = false;
            released = true;
            return;
          }
          inPlace = a != null && a.handle != null && a.ctx != null;
        }
        if (a == null) {
          // Not running (an earlier start failed): try again with the new config; tryStart() reads the
          // latest config itself, and does nothing if services are missing.
          triedStart = true;
          tryStart(true);
          continue;
        }
        triedStart = false;
        if (inPlace && reconfigureInPlace(a, target)) continue;
        restart(a);
        triedStart = true;    // restart() already tried to start; do not retry in a loop if it could not
      }
    } finally {
      if (!released) {
        synchronized (reg.lock) {
          updating = false;    // leaving by an exception: let the next update() take over
        }
      }
    }
  }

  /**
   * Runs reconfigure() as an effect inside the instance's own fiber: if the instance is being disposed,
   * the effect is refused (then: restart path); if it is disposed while reconfigure() runs, the core
   * waits for it. Returns true if reconfigure() handled the change.
   */
  private boolean reconfigureInPlace(Activation a, Object target) {
    Object previous;
    synchronized (reg.lock) {
      previous = a.config;
    }
    boolean[] handled = new boolean[1];
    try {
      a.ctx.fiber.effect("reconfigure", signal -> {
        handled[0] = body.reconfigure(a.ctx, previous, target);
        return null;
      });
    } catch (FiberClosedException closed) {
      // The instance is going away. Take the restart path: it detaches it if still current and starts a
      // new one with the new config only if the host is alive and its services exist, so the loop ends.
      if (!a.ctx.fiber.isAlive()) return false;
      throw closed;
    } catch (Throwable t) {
      // The instance may be half-updated: stop it rather than leave it inconsistent, then report.
      if (t instanceof VirtualMachineError e) throw e;
      try {
        stopIfCurrent(a);
      } catch (Throwable more) {
        if (more != t) t.addSuppressed(more);
      }
      throw sneakyThrow(t);
    }
    if (!handled[0]) return false;
    synchronized (reg.lock) {
      a.config = target;
    }
    return true;
  }

  private void restart(Activation a) {
    stopIfCurrent(a);
    tryStart(true);
  }

  private void stopIfCurrent(Activation a) {
    boolean mine;
    synchronized (reg.lock) {
      mine = current == a;
      if (mine) detachLocked();
    }
    if (mine) a.stop();
  }

  // ---------------- helpers ----------------

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
