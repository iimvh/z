package core;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects deadlocks BEFORE waiting.
 *
 * Each thread about to wait inside the core records an edge "I am waiting for thread X". Before
 * recording it, follow the chain of edges starting at X; if it leads back to the waiting thread, the
 * wait would close a cycle. In that case tryEnter() returns false and the caller must do something
 * else instead of waiting.
 *
 * Failure conditions and how they are handled:
 *  - Two threads close a cycle at the same time    -> every tryEnter runs sequentially under one lock;
 *                                                      the edge that closes the cycle is always detected
 *                                                      by the thread that adds it.
 *  - Infinite walk while checking                  -> an edge that would create a cycle is never added,
 *                                                      so the graph is always acyclic and the walk terminates.
 *  - Waiting on oneself                            -> owner == me is refused immediately.
 *  - Orphaned edge (thread dies while waiting)     -> callers always call exit() in a finally block.
 *  - Deadlock between two locks                    -> this lock never acquires any other lock.
 *
 * Limitation (cannot be fixed inside the core): only waits performed by the core are visible. If user
 * code blocks (join, latch, I/O, ...) while waiting for a thread that is waiting on the core, that
 * cycle is outside what this graph can see.
 */
final class WaitGraph {

  static final WaitGraph GLOBAL = new WaitGraph();

  private final Map<Thread, Thread> waitsFor = new HashMap<>();

  synchronized boolean tryEnter(Thread me, Thread owner) {
    if (me == null || owner == null) throw new NullPointerException("me/owner");
    if (waitsFor.containsKey(me)) {
      throw new IllegalStateException("Core bug: thread " + me.getName() + " is already waiting");
    }
    for (Thread t = owner; t != null; t = waitsFor.get(t)) {
      if (t == me) return false;
    }
    waitsFor.put(me, owner);
    return true;
  }

  synchronized void exit(Thread me) {
    waitsFor.remove(me);
  }

  synchronized Thread waitingFor(Thread t) {
    return waitsFor.get(t);
  }

  synchronized int size() {
    return waitsFor.size();
  }

}
