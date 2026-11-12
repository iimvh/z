package core;

/**
 * One entry in the undo list: a piece of work that has run (or is running), and how to undo it.
 *
 * State machine. Every other transition throws IllegalStateException, because it is a bug inside the
 * core and must surface immediately instead of silently corrupting data:
 *
 *   PENDING --activate--> ACTIVE --beginRelease--> RELEASING --finishRelease--> RELEASED
 *      |                     ^                         |
 *      |                     +-------- revert ---------+  (the releaser had to defer)
 *      +--fail--------> FAILED    (run/apply threw: nothing to undo)
 *      +--settleEmpty-> RELEASED  (run returned null: nothing to undo)
 *
 * owner: PENDING -> the thread running run(); RELEASING -> the thread releasing it; otherwise null.
 * finishRelease/revert may only be called by the owning thread.
 * Once settled (RELEASED/FAILED), the slot drops its references to the undo and the child fiber so
 * that nothing is retained in memory.
 *
 * No internal synchronization: Fiber calls every method while holding the lock of the fiber that contains the slot.
 */
final class Slot {

  enum State { PENDING, ACTIVE, RELEASING, RELEASED, FAILED }

  final String name;
  final CancelSource signal;
  private Fiber child;               // non-null: this slot belongs to a component (a scope)
  private State state = State.PENDING;
  private Thread owner;
  private Disposable undo;
  private boolean releaseRequested;  // a release was requested but had to be deferred

  // Linked-list fields: only UndoStack touches these.
  UndoStack list;
  Slot prev;
  Slot next;

  Slot(String name, Fiber child, CancelSource signal, Thread runner) {
    if (runner == null) throw new NullPointerException("runner");
    this.name = name;
    this.child = child;
    this.signal = signal;
    this.owner = runner;
  }

  State state() {
    return state;
  }

  Thread owner() {
    return owner;
  }

  Disposable undo() {
    return undo;
  }

  Fiber child() {
    return child;
  }

  boolean releaseRequested() {
    return releaseRequested;
  }

  boolean isSettled() {
    return state == State.RELEASED || state == State.FAILED;
  }

  // ---- state transitions ----

  void activate(Disposable undo) {
    require(State.PENDING, "activate");
    this.undo = undo;
    state = State.ACTIVE;
    owner = null;
  }

  void fail() {
    require(State.PENDING, "fail");
    state = State.FAILED;
    owner = null;
    child = null;
  }

  void settleEmpty() {
    require(State.PENDING, "settleEmpty");
    if (child != null) throw new IllegalStateException("Core bug: settleEmpty on component slot '" + name + "'");
    state = State.RELEASED;
    owner = null;
  }

  void beginRelease(Thread releaser) {
    if (releaser == null) throw new NullPointerException("releaser");
    require(State.ACTIVE, "beginRelease");
    state = State.RELEASING;
    owner = releaser;
  }

  void finishRelease(Thread releaser) {
    require(State.RELEASING, "finishRelease");
    requireOwner(releaser, "finishRelease");
    state = State.RELEASED;
    owner = null;
    undo = null;
    child = null;
  }

  void revert(Thread releaser) {
    require(State.RELEASING, "revert");
    requireOwner(releaser, "revert");
    state = State.ACTIVE;
    owner = null;
    releaseRequested = true;
  }

  private void require(State expected, String op) {
    if (state != expected) {
      throw new IllegalStateException("Core bug: " + op + " while slot '" + name + "' is " + state);
    }
  }

  private void requireOwner(Thread t, String op) {
    if (owner != t) {
      throw new IllegalStateException("Core bug: " + op + " on slot '" + name + "' by "
          + nameOf(t) + " but the owner is " + nameOf(owner));
    }
  }

  static String nameOf(Thread t) {
    return t == null ? "null" : t.getName();
  }

}
