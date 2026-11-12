package core;

/**
 * The lifecycle of a fiber: ALIVE -> DISPOSING -> DISPOSED, one direction only.
 *
 * While DISPOSING, exactly one of the following holds:
 *   - disposer != null : a thread is disposing it;
 *   - disposer == null : the disposal is SUSPENDED (deferred because waiting would deadlock). The next
 *                        thread that needs this fiber takes over the disposal (RESUMED).
 *
 * Failure conditions and how they are handled:
 *  - Two threads dispose the same fiber      -> begin() hands the disposer role to one thread only; the
 *                                               other gets BUSY and must wait.
 *  - Waiting on oneself (nested call)        -> the thread already disposing gets REENTRANT, not BUSY.
 *  - Invalid state change / finishing early  -> finish()/release() called by a thread that is not the
 *                                               disposer, or outside DISPOSING, throw (a core bug).
 *  - A deferred disposal is forgotten        -> disposer == null is a valid state; the blocking thread
 *                                               is recorded (blockedBy) for diagnosis and to prevent that
 *                                               same thread from waiting on itself.
 *
 * No internal synchronization: Fiber calls it while holding its own lock.
 */
final class Lifecycle {

  enum State { ALIVE, DISPOSING, DISPOSED }

  enum Role {
    STARTED,   // ALIVE -> DISPOSING; the caller is the disposer and must signal cancellation
    RESUMED,   // the disposal was suspended; the caller takes over the disposer role
    REENTRANT, // the caller IS the thread currently disposing (called from inside an undo/listener)
    BUSY,      // another thread is disposing
    DONE       // already DISPOSED
  }

  private State state = State.ALIVE;
  private Thread disposer;
  private Thread blockedBy;

  Role begin(Thread me) {
    if (me == null) throw new NullPointerException("me");
    switch (state) {
      case ALIVE:
        state = State.DISPOSING;
        disposer = me;
        return Role.STARTED;
      case DISPOSED:
        return Role.DONE;
      default:
        if (disposer == null) {
          disposer = me;
          blockedBy = null;
          return Role.RESUMED;
        }
        return disposer == me ? Role.REENTRANT : Role.BUSY;
    }
  }

  /** The disposer suspends the disposal (defers), recording the thread that blocks it. */
  void release(Thread me, Thread blocker) {
    requireDisposer(me, "release");
    disposer = null;
    blockedBy = blocker;
  }

  /** The disposer has finished disposing. */
  void finish(Thread me) {
    requireDisposer(me, "finish");
    state = State.DISPOSED;
    disposer = null;
    blockedBy = null;
  }

  State state() {
    return state;
  }

  boolean isAlive() {
    return state == State.ALIVE;
  }

  Thread disposer() {
    return disposer;
  }

  Thread blockedBy() {
    return blockedBy;
  }

  private void requireDisposer(Thread me, String op) {
    if (state != State.DISPOSING || disposer != me) {
      throw new IllegalStateException("Core bug: " + op + " while state=" + state
          + ", disposer=" + Slot.nameOf(disposer) + ", called by=" + Slot.nameOf(me));
    }
  }

}
