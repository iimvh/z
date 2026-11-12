package core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The teardown engine.
 *
 * Runs ITERATIVELY over an explicit work stack (no recursion), so the depth of the tree is unbounded.
 * Each frame is one of two kinds:
 *   - dispose fiber F: take F's newest slot, undo it, repeat until F is empty, then mark F DISPOSED;
 *   - finish component slot S: S's child fiber is DISPOSED, so run S's own undo.
 * When a component slot is met, push "finish S", then push "dispose the child" on top: the child
 * finishes first, the parent second.
 *
 * When it has to wait (the slot is being run or released by another thread, or the child fiber is
 * being disposed by another thread):
 *   - if waiting is safe, it waits (WaitGraph guarantees the wait never closes a deadlock cycle);
 *   - if waiting is impossible (waiting on itself, or it would close a cycle), it DEFERS: it pops the
 *     whole stack and leaves the fibers in the suspended state (disposer = null). The thread holding
 *     the blocking work resumes the teardown once that work finishes, in the correct order, from the
 *     exact point where it stopped.
 *
 * "Opportunistic" frames (mayWait = false) are pushed when a thread happens to finish work inside a
 * suspended fiber. That thread helps continue the teardown but never waits; if it hits something
 * it would have to wait for, it defers again.
 *
 * Lock discipline: at most ONE fiber lock is held at any time, and user code (undo, listeners)
 * always runs while holding no core lock.
 */
final class Teardown {

  private Teardown() {
  }

  private static final class Frame {
    final Fiber fiber;   // scope == null: the fiber to dispose; otherwise: the fiber that contains slot `scope`
    final Slot scope;    // non-null: finish this component slot
    final boolean mayWait;

    Frame(Fiber fiber, Slot scope, boolean mayWait) {
      this.fiber = fiber;
      this.scope = scope;
      this.mayWait = mayWait;
    }
  }

  // ---------------- entry points ----------------

  /** fiber.dispose(): disposes the fiber, waiting when needed. Returns early only when waiting is impossible. */
  static void disposeFiber(Fiber f, Errors errors) {
    Deque<Frame> work = new ArrayDeque<>();
    if (begin(f, work, errors, true) != null) return;
    run(work, errors);
  }

  /** handle.dispose(): disposes exactly one slot (and its subtree if it is a component). */
  static void releaseSlot(Fiber f, Slot s, Errors errors) {
    Thread me = Thread.currentThread();
    synchronized (f.lock) {
      for (;;) {
        Slot.State st = s.state();
        if (st == Slot.State.RELEASED || st == Slot.State.FAILED) return;
        if (st == Slot.State.ACTIVE) {
          s.beginRelease(me);
          break;
        }
        if (st == Slot.State.RELEASING) {
          Thread owner = s.owner();
          if (owner == me || !await(f, owner, errors)) return; // the thread releasing it will finish
          continue;
        }
        throw new IllegalStateException("Core bug: handle of slot '" + s.name + "' is " + st);
      }
    }
    s.signal.cancel(errors);
    Deque<Frame> work = new ArrayDeque<>();
    Fiber child = s.child();
    if (child == null) {
      runUndo(s, errors);
      finalizeRelease(f, s, work);
    } else {
      work.push(new Frame(f, s, true));
      Thread blocker = begin(child, work, errors, true);
      if (blocker != null) {
        defer(work, blocker);
        return;
      }
    }
    run(work, errors);
  }

  /** A piece of work just finished in a fiber being disposed: if nobody is disposing it, help continue. */
  static void resumeIfIdle(Fiber f, Errors errors) {
    Deque<Frame> work = new ArrayDeque<>();
    maybeResume(f, work);
    run(work, errors);
  }

  /** Waits while holding f.lock. Returns false if waiting is impossible (waiting on itself, or a cycle). */
  static boolean await(Fiber f, Thread owner, Errors errors) {
    Thread me = Thread.currentThread();
    if (owner == null) return true;
    if (owner == me || !WaitGraph.GLOBAL.tryEnter(me, owner)) return false;
    try {
      f.lock.wait(1000); // bounded: a safety net that re-checks the state periodically
    } catch (InterruptedException e) {
      errors.noteInterrupted(); // do not abandon the teardown; the interrupt flag is restored at the end
    } finally {
      WaitGraph.GLOBAL.exit(me);
    }
    return true;
  }

  // ---------------- main loop ----------------

  private static void run(Deque<Frame> work, Errors errors) {
    while (!work.isEmpty()) {
      Frame top = work.peek();
      Thread blocker = top.scope == null ? step(top, work, errors) : finishScope(top, work, errors);
      if (blocker != null) {
        defer(work, blocker);
        return;
      }
    }
  }

  /**
   * Starts (or takes over) the disposal of fiber f. Returns null if a frame was pushed or f is already
   * finished; returns the blocking thread if the work cannot proceed.
   */
  private static Thread begin(Fiber f, Deque<Frame> work, Errors errors, boolean mayWait) {
    Thread me = Thread.currentThread();
    boolean fireCancel = false;
    synchronized (f.lock) {
      for (;;) {
        Lifecycle.Role role = f.life.begin(me);
        if (role == Lifecycle.Role.STARTED) {
          fireCancel = true;
          work.push(new Frame(f, null, mayWait));
          break;
        }
        if (role == Lifecycle.Role.RESUMED) {
          work.push(new Frame(f, null, mayWait));
          break;
        }
        if (role == Lifecycle.Role.DONE) return null;
        if (role == Lifecycle.Role.REENTRANT) return me;
        Thread owner = f.life.disposer(); // BUSY
        if (!mayWait || !await(f, owner, errors)) return owner;
      }
    }
    if (fireCancel) f.signal.cancel(errors);
    return null;
  }

  /** One disposal step for a fiber: handles its newest slot. */
  private static Thread step(Frame frame, Deque<Frame> work, Errors errors) {
    Fiber f = frame.fiber;
    Thread me = Thread.currentThread();
    Slot s;
    synchronized (f.lock) {
      for (;;) {
        s = f.stack.last();
        if (s == null) {
          f.life.finish(me);
          f.lock.notifyAll();
          break;
        }
        Slot.State st = s.state();
        if (st == Slot.State.ACTIVE) {
          s.beginRelease(me);
          break;
        }
        if (st == Slot.State.PENDING || st == Slot.State.RELEASING) {
          // PENDING: run()/apply() is still running; RELEASING: being released individually through its handle.
          Thread owner = s.owner();
          if (owner == me || !frame.mayWait || !await(f, owner, errors)) return owner;
          continue;
        }
        throw new IllegalStateException("Core bug: slot '" + s.name + "' is " + st + " but still in the list");
      }
    }
    if (s == null) {
      work.pop();
      afterDisposed(f, work);
      return null;
    }
    s.signal.cancel(errors);
    Fiber child = s.child();
    if (child == null) {
      runUndo(s, errors);
      finalizeRelease(f, s, work);
      return null;
    }
    work.push(new Frame(f, s, frame.mayWait));
    return begin(child, work, errors, frame.mayWait);
  }

  /** The child fiber of a component slot is DISPOSED: run the component's own undo, then remove the slot. */
  private static Thread finishScope(Frame frame, Deque<Frame> work, Errors errors) {
    Slot s = frame.scope;
    Fiber child = s.child();
    if (child != null && !child.isDisposed()) {
      throw new IllegalStateException("Core bug: finishing component '" + s.name + "' before its child fiber is disposed");
    }
    runUndo(s, errors);
    work.pop();
    finalizeRelease(frame.fiber, s, work);
    return null;
  }

  private static void runUndo(Slot s, Errors errors) {
    Disposable undo = s.undo();
    if (undo == null) return;
    try {
      undo.dispose();
    } catch (Throwable t) {
      errors.add(s.name, t);
    }
  }

  private static void finalizeRelease(Fiber f, Slot s, Deque<Frame> work) {
    synchronized (f.lock) {
      f.stack.remove(s);
      s.finishRelease(Thread.currentThread());
      f.lock.notifyAll();
    }
    s.signal.detach();
    maybeResume(f, work);
  }

  /**
   * Fiber c has just been DISPOSED. If no frame is left below it (no parent frame is waiting for it on
   * this stack), report to the parent: finish the component slot if someone asked for its release, or
   * continue the parent's disposal if it was suspended.
   */
  private static void afterDisposed(Fiber c, Deque<Frame> work) {
    if (!work.isEmpty()) return;
    Fiber p = c.parent;
    if (p == null) return;
    Slot x = c.scopeSlot;
    synchronized (p.lock) {
      if (x.state() == Slot.State.ACTIVE && x.releaseRequested()) {
        x.beginRelease(Thread.currentThread());
        work.push(new Frame(p, x, false));
        return;
      }
    }
    maybeResume(p, work);
  }

  /** A fiber is suspended (DISPOSING with no disposer): the current thread takes over in opportunistic mode. */
  private static void maybeResume(Fiber f, Deque<Frame> work) {
    synchronized (f.lock) {
      if (f.life.state() != Lifecycle.State.DISPOSING || f.life.disposer() != null) return;
      f.life.begin(Thread.currentThread()); // RESUMED
      work.push(new Frame(f, null, false));
    }
  }

  /** Defers: pops the whole stack and returns the fibers to the suspended state. */
  private static void defer(Deque<Frame> work, Thread blocker) {
    Thread me = Thread.currentThread();
    while (!work.isEmpty()) {
      Frame fr = work.pop();
      synchronized (fr.fiber.lock) {
        if (fr.scope == null) fr.fiber.life.release(me, blocker);
        else fr.scope.revert(me);
        fr.fiber.lock.notifyAll();
      }
    }
  }

}
