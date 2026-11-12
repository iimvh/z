package core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A node in the teardown tree. Composes the core components:
 * Errors (error collection), CancelSource (cancellation signal), Slot + UndoStack (undo list),
 * Lifecycle (state machine), WaitGraph (deadlock avoidance), and Teardown (iterative disposer).
 *
 * Guarantees:
 *  1. Ordering: disposal runs in reverse START order (a slot is reserved before its run() starts),
 *     children before parents. This holds even when run() creates more work on its own fiber.
 *  2. Exactly once: each undo runs at most once, even when several threads dispose concurrently.
 *  3. No silent loss: an error in one undo does not stop the others. All errors are collected into
 *     a DisposeException that names the failing source. Fatal JVM errors are rethrown unchanged.
 *  4. Completion: a normal return from dispose() means the fiber is DISPOSED. The only exception is
 *     when waiting is impossible: the calling thread is itself holding unfinished work that the
 *     teardown needs (calling dispose() from inside run() or undo() of the same tree), or waiting
 *     would close a deadlock cycle. In that case teardown stops at that point and resumes in the
 *     correct order as soon as the unfinished work completes.
 *  5. Closing: once teardown has started, effect()/use() throw FiberClosedException and run() is
 *     not called. Work already running is notified through its Signal and is undone when it returns.
 *  6. Unbounded depth: teardown and cancellation propagation are iterative, not recursive.
 *  7. Isolation: a component only receives its own fiber; there is no path upward to the parent;
 *     child fibers can only be created through use(); roots are created only through Fiber.root().
 *  8. Bounded memory: work that needs no undo is not retained; disposed work does not keep links
 *     or signals alive; each fiber has a slot quota.
 *  9. User code (run, undo, listeners) never executes while the core holds one of its locks.
 *
 * What the core CANNOT guarantee (JVM limits or contract with the user):
 *  - Forcibly stopping a hung run() or undo(): Java has no safe way to stop a thread. The core helps
 *    DETECT such hangs: awaitDisposed(timeout) and describe().
 *  - Cleaning up what run()/apply() opened but never returned an undo for (the Effect contract).
 *  - Deadlocks caused by user code that blocks (join, latch, ...) on a thread that is waiting on the core.
 *  - A process killed abruptly (kill -9): no code runs after that.
 */
public final class Fiber implements Disposable {

  public static final int DEFAULT_MAX_SLOTS = 100_000;

  private static final int DESCRIBE_LINE_LIMIT = 500;
  private static final Disposable NOOP = new Noop();

  final Object lock = new Object();
  final String name;
  final Fiber parent;          // NOT exposed outside the package
  final CancelSource signal;
  final int maxSlots;
  final UndoStack stack = new UndoStack();
  final Lifecycle life = new Lifecycle();
  Slot scopeSlot;              // the slot in parent that owns this fiber; assigned once before it is exposed

  private Fiber(String name, Fiber parent, CancelSource signal, int maxSlots) {
    this.name = name;
    this.parent = parent;
    this.signal = signal;
    this.maxSlots = maxSlots;
  }

  /** Creates a new tree. This is the ONLY way to obtain a fiber without a parent. */
  public static Fiber root(String name) {
    return root(name, DEFAULT_MAX_SLOTS);
  }

  public static Fiber root(String name, int maxSlotsPerFiber) {
    if (maxSlotsPerFiber <= 0) throw new IllegalArgumentException("maxSlotsPerFiber must be > 0");
    String n = name == null ? "root" : name;
    return new Fiber(n, null, new CancelSource(n), maxSlotsPerFiber);
  }

  public String name() {
    return name;
  }

  /** Cancellation signal of this fiber: cancelled as soon as the fiber starts disposing. */
  public Signal signal() {
    return signal;
  }

  public boolean isAlive() {
    synchronized (lock) {
      return life.isAlive();
    }
  }

  public boolean isDisposed() {
    synchronized (lock) {
      return life.state() == Lifecycle.State.DISPOSED;
    }
  }

  /** Number of work items this fiber currently manages (not yet disposed). */
  public int size() {
    synchronized (lock) {
      return stack.size();
    }
  }

  // ---------------- creating work ----------------

  public Disposable effect(Effect effect) {
    return effect(null, effect);
  }

  /** Runs a piece of work and keeps its undo. Returns a handle that undoes only this work. */
  public Disposable effect(String name, Effect effect) {
    Objects.requireNonNull(effect, "effect");
    Slot slot = reserve(nameOf(name, effect, "effect"), false);
    Disposable undo = null;
    Throwable failure = null;
    try {
      undo = effect.run(slot.signal);
    } catch (Throwable t) {
      failure = t;
    }
    return complete(slot, undo, failure, new Errors());
  }

  public Disposable use(Component component) {
    return use(null, component);
  }

  /** Runs a component inside its own child fiber. Returns a handle that undoes the whole branch. */
  public Disposable use(String name, Component component) {
    Objects.requireNonNull(component, "component");
    Slot slot = reserve(nameOf(name, component, "component"), true);
    Fiber child = slot.child();
    Disposable own = null;
    Throwable failure = null;
    try {
      own = component.apply(child);
    } catch (Throwable t) {
      failure = t;
    }
    Errors errors = new Errors();
    if (failure != null) Teardown.disposeFiber(child, errors); // undo whatever was created before the failure
    return complete(slot, own, failure, errors);
  }

  // ---------------- disposal ----------------

  /** Undoes everything in this fiber, in reverse creation order. See guarantee 4 at the top of the file. */
  @Override
  public void dispose() {
    Errors errors = new Errors();
    Teardown.disposeFiber(this, errors);
    errors.throwIfAny();
  }

  /**
   * Waits until this fiber is disposed (does not start the disposal itself). Returns false on timeout.
   * Throws DeadlockException if the wait can never complete (for example, when called from inside
   * an undo of this same fiber).
   */
  public boolean awaitDisposed(long timeout, TimeUnit unit) throws InterruptedException {
    Objects.requireNonNull(unit, "unit");
    Thread me = Thread.currentThread();
    long total = unit.toNanos(Math.max(0, timeout));
    long start = System.nanoTime();
    synchronized (lock) {
      for (;;) {
        if (life.state() == Lifecycle.State.DISPOSED) return true;
        Thread owner = life.disposer();
        if (owner == me || (owner == null && life.blockedBy() == me)) {
          throw new DeadlockException("Thread " + me.getName() + " is waiting for fiber '" + name
              + "' to finish disposing, but the disposal needs this same thread to finish its own work first");
        }
        long remaining = total - (System.nanoTime() - start);
        if (remaining <= 0) return false;
        boolean entered = false;
        if (owner != null) {
          if (!WaitGraph.GLOBAL.tryEnter(me, owner)) {
            throw new DeadlockException("Waiting for fiber '" + name + "' would close a deadlock cycle with thread "
                + owner.getName());
          }
          entered = true;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, TimeUnit.SECONDS.toNanos(1)));
        } finally {
          if (entered) WaitGraph.GLOBAL.exit(me);
        }
      }
    }
  }

  /** Snapshot of the whole tree, for diagnosing hangs. The most recent item (disposed first) is listed first. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    Deque<Item> todo = new ArrayDeque<>();
    todo.push(new Item(this, 0));
    int lines = 0;
    while (!todo.isEmpty() && lines < DESCRIBE_LINE_LIMIT) {
      Item item = todo.pop();
      List<Fiber> kids = new ArrayList<>();
      synchronized (item.fiber.lock) {
        Fiber f = item.fiber;
        indent(sb, item.depth).append("fiber '").append(f.name).append("' ").append(f.life.state());
        Thread d = f.life.disposer();
        Thread b = f.life.blockedBy();
        if (d != null) sb.append(" (being disposed by ").append(d.getName()).append(')');
        else if (b != null) sb.append(" (suspended, waiting for ").append(b.getName()).append(')');
        sb.append('\n');
        lines++;
        List<Slot> slots = f.stack.snapshot();
        for (int i = slots.size() - 1; i >= 0 && lines < DESCRIBE_LINE_LIMIT; i--) {
          Slot s = slots.get(i);
          indent(sb, item.depth + 1).append("- [").append(s.state());
          if (s.owner() != null) sb.append(" by ").append(s.owner().getName());
          sb.append("] ").append(s.name).append('\n');
          lines++;
          if (s.child() != null) kids.add(s.child());
        }
      }
      for (int i = kids.size() - 1; i >= 0; i--) todo.push(new Item(kids.get(i), item.depth + 2));
    }
    if (!todo.isEmpty()) sb.append("... (truncated)\n");
    return sb.toString();
  }

  @Override
  public String toString() {
    synchronized (lock) {
      return "Fiber[" + name + ", " + life.state() + ", " + stack.size() + " items]";
    }
  }

  // ---------------- internals ----------------

  // Reserves the slot BEFORE running: disposal order = start order, and there is no gap between
  // allocation and registration after run().
  private Slot reserve(String slotName, boolean scope) {
    Thread me = Thread.currentThread();
    synchronized (lock) {
      if (!life.isAlive()) {
        throw new FiberClosedException("Fiber '" + name + "' has started disposing; rejecting new work '" + slotName + "'");
      }
      if (stack.size() >= maxSlots) {
        throw new IllegalStateException("Fiber '" + name + "' is full (" + maxSlots + " items); rejecting '"
            + slotName + "'. Dispose some work, or split it into child fibers.");
      }
      CancelSource slotSignal = signal.child(slotName);
      Fiber child = scope ? new Fiber(slotName, this, slotSignal.child(slotName), maxSlots) : null;
      Slot slot = new Slot(slotName, child, slotSignal, me);
      if (child != null) child.scopeSlot = slot;
      stack.push(slot);
      return slot;
    }
  }

  // Called when run()/apply() has returned: records the result, or hands over to teardown if the fiber closed meanwhile.
  private Disposable complete(Slot slot, Disposable undo, Throwable failure, Errors errors) {
    boolean settled;
    boolean closed;
    synchronized (lock) {
      if (failure != null) {
        stack.remove(slot);
        slot.fail();
        settled = true;
      } else if (undo == null && slot.child() == null) {
        stack.remove(slot);       // nothing to undo: do not retain it
        slot.settleEmpty();
        settled = true;
      } else {
        slot.activate(undo);
        settled = false;
      }
      lock.notifyAll();
      closed = !life.isAlive();
    }
    if (settled) slot.signal.detach();
    if (closed) Teardown.resumeIfIdle(this, errors);
    if (failure != null) throw Errors.sneakyThrow(errors.mergeInto(failure));
    if (closed) {
      FiberClosedException e = new FiberClosedException("Fiber '" + name + "' was disposed while '"
          + slot.name + "' was running; its result has been or will be cleaned up automatically, do not use it");
      throw Errors.sneakyThrow(errors.mergeInto(e));
    }
    errors.throwIfAny();
    return settled ? NOOP : new Handle(this, slot);
  }

  private static String nameOf(String given, Object fn, String fallback) {
    if (given != null) return given;
    String n = fn.getClass().getSimpleName();
    return n.isEmpty() || n.contains("$$Lambda") ? fallback : n;
  }

  private static StringBuilder indent(StringBuilder sb, int depth) {
    for (int i = 0, n = Math.min(depth, 40); i < n; i++) sb.append("  ");
    return sb;
  }

  private record Item(Fiber fiber, int depth) {
  }

  // Returned by effect()/use(): undoes exactly this work item.
  private static final class Handle implements Disposable {
    private final Fiber fiber;
    private final Slot slot;

    Handle(Fiber fiber, Slot slot) {
      this.fiber = fiber;
      this.slot = slot;
    }

    @Override
    public void dispose() {
      Errors errors = new Errors();
      Teardown.releaseSlot(fiber, slot, errors);
      errors.throwIfAny();
    }
  }

  private static final class Noop implements Disposable {
    @Override
    public void dispose() {
    }
  }

}
