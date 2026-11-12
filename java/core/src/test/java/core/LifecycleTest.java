package core;

final class LifecycleTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static void run() {
    Check.section("Component 4: Lifecycle (fiber lifecycle)");
    Thread me = Thread.currentThread();
    Thread other = new Thread(() -> { });

    Lifecycle l = new Lifecycle();
    Check.ok("ALIVE initially", l.isAlive());
    Check.ok("finish while ALIVE -> throws", Check.caught(() -> l.finish(me)) instanceof IllegalStateException);
    Check.ok("release while ALIVE -> throws",
        Check.caught(() -> l.release(me, null)) instanceof IllegalStateException);

    Check.eq("first call: STARTED", Lifecycle.Role.STARTED, l.begin(me));
    Check.ok("DISPOSING, and the caller is the disposer",
        l.state() == Lifecycle.State.DISPOSING && l.disposer() == me);
    Check.eq("same thread calls again: REENTRANT", Lifecycle.Role.REENTRANT, l.begin(me));
    Check.eq("another thread: BUSY (no concurrent disposal)", Lifecycle.Role.BUSY, l.begin(other));
    Check.ok("another thread cannot finish",
        Check.caught(() -> l.finish(other)) instanceof IllegalStateException);

    l.release(me, other);
    Check.ok("deferred: no disposer, and the blocking thread is recorded",
        l.disposer() == null && l.blockedBy() == other);
    Check.ok("a deferred disposal does not return to ALIVE", !l.isAlive());
    Check.eq("another thread takes over: RESUMED", Lifecycle.Role.RESUMED, l.begin(other));
    Check.ok("taking over clears the blocking-thread record", l.disposer() == other && l.blockedBy() == null);

    l.finish(other);
    Check.ok("finish -> DISPOSED", l.state() == Lifecycle.State.DISPOSED && l.disposer() == null);
    Check.eq("after completion: DONE", Lifecycle.Role.DONE, l.begin(me));
    Check.ok("finish twice -> throws", Check.caught(() -> l.finish(other)) instanceof IllegalStateException);
  }

}
