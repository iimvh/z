package core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A hierarchical cancellation signal: cancelling a parent cancels all of its descendants.
 *
 * Failure conditions and how they are handled:
 *  - A listener runs 0 or 2 times when registering -> registration and cancellation are decided
 *    under one lock: the listener is either added BEFORE the cancelling thread takes the snapshot
 *    (and is then run by it), or it sees the signal already cancelled (and runs immediately). The
 *    list is cleared when the snapshot is taken, so nobody runs it twice.
 *  - A listener throws and blocks later listeners -> each listener is wrapped in try/catch; errors
 *    are collected into Errors.
 *  - Deadlock because a listener calls back into the core -> listeners run OUTSIDE the lock; a
 *    CancelSource lock is never held together with another one.
 *  - Stack overflow on a very deep tree -> cancellation propagates with a queue (iterative), not recursion.
 *  - Memory leak: listeners and children accumulate -> unregistering and detach() remove entries in
 *    O(1); after cancellation the collections are dropped. Collections are allocated lazily.
 *  - A child is created AFTER its parent was cancelled -> the child is created already cancelled.
 */
final class CancelSource implements Signal {

  private static final Disposable NOOP = new Noop();

  final String label;
  private final Object lock = new Object();
  private volatile boolean cancelled;
  private CancelSource parent;              // used by detach(); null if this is a root or already detached
  private Set<Registration> listeners;      // null: nobody registered, or already cancelled
  private Set<CancelSource> children;       // null: no children, or already cancelled

  CancelSource(String label) {
    this.label = label;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void throwIfCancelled() {
    if (cancelled) throw new CancelledException("'" + label + "' has been cancelled");
  }

  @Override
  public Disposable onCancel(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (lock) {
      if (!cancelled) {
        Registration r = new Registration(this, listener);
        if (listeners == null) listeners = new LinkedHashSet<>();
        listeners.add(r);
        return r;
      }
    }
    listener.run(); // already cancelled: run immediately, outside the lock
    return NOOP;
  }

  /** Creates a child signal. If this signal is already cancelled, the child is created cancelled. */
  CancelSource child(String childLabel) {
    CancelSource c = new CancelSource(childLabel);
    synchronized (lock) {
      if (!cancelled) {
        c.parent = this;
        if (children == null) children = new LinkedHashSet<>();
        children.add(c);
        return c;
      }
    }
    c.cancelled = true;
    return c;
  }

  /** Detaches from the parent (once the owner of this signal is done), so the parent does not keep it alive. */
  void detach() {
    CancelSource p;
    synchronized (lock) {
      p = parent;
      parent = null;
    }
    if (p == null) return;
    synchronized (p.lock) {
      if (p.children != null) p.children.remove(this);
    }
  }

  /**
   * Cancels this signal and all of its descendants. Listener errors are collected into {@code errors}.
   * Calling it again after cancellation does nothing.
   */
  void cancel(Errors errors) {
    ArrayDeque<CancelSource> work = new ArrayDeque<>();
    work.add(this);
    while (!work.isEmpty()) {
      CancelSource s = work.poll();
      List<Registration> ls;
      List<CancelSource> cs;
      synchronized (s.lock) {
        if (s.cancelled) continue;
        s.cancelled = true;
        ls = s.listeners == null ? List.of() : new ArrayList<>(s.listeners);
        cs = s.children == null ? List.of() : new ArrayList<>(s.children);
        s.listeners = null;
        s.children = null;
      }
      for (Registration r : ls) {
        try {
          r.listener.run();
        } catch (Throwable t) {
          errors.add("onCancel:" + s.label, t);
        }
      }
      work.addAll(cs);
    }
  }

  // ---- for tests ----

  int listenerCount() {
    synchronized (lock) {
      return listeners == null ? 0 : listeners.size();
    }
  }

  int childCount() {
    synchronized (lock) {
      return children == null ? 0 : children.size();
    }
  }

  // ---- nested classes ----

  private static final class Registration implements Disposable {
    private final CancelSource source;
    private final Runnable listener;

    Registration(CancelSource source, Runnable listener) {
      this.source = source;
      this.listener = listener;
    }

    @Override
    public void dispose() {
      synchronized (source.lock) {
        if (source.listeners != null) source.listeners.remove(this);
      }
    }
  }

  private static final class Noop implements Disposable {
    @Override
    public void dispose() {
    }
  }

}
