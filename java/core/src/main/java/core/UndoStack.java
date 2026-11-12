package core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The undo list: a doubly linked list, LIFO, every operation O(1).
 *
 * Failure conditions and how they are handled:
 *  - Removing a slot that is not in this list -> corrupts another fiber's list. Prevented: each slot
 *    remembers its owning list (slot.list); a mismatch throws.
 *  - Removing twice / adding twice             -> slot.list records where it is; a mismatch throws.
 *  - Keeping stale links through prev/next     -> on removal, the slot's prev/next/list are cleared to null
 *    (the former flaw #6).
 *  - Wrong size count                          -> size only changes in push/remove; tests compare it
 *    with the number of nodes actually reachable.
 *
 * No internal synchronization: Fiber calls it while holding its own lock.
 */
final class UndoStack {

  private Slot tail;
  private int size;

  void push(Slot s) {
    if (s == null) throw new NullPointerException("slot");
    if (s.list != null || s.prev != null || s.next != null) {
      throw new IllegalStateException("Core bug: slot '" + s.name + "' is already in a list");
    }
    s.list = this;
    s.prev = tail;
    if (tail != null) tail.next = s;
    tail = s;
    size++;
  }

  void remove(Slot s) {
    if (s == null) throw new NullPointerException("slot");
    if (s.list != this) {
      throw new IllegalStateException("Core bug: slot '" + s.name + "' is not in this list");
    }
    if (s.prev != null) s.prev.next = s.next;
    if (s.next != null) s.next.prev = s.prev;
    else tail = s.prev;
    s.prev = null;
    s.next = null;
    s.list = null;
    size--;
  }

  /** The newest slot (disposed first), or null if empty. */
  Slot last() {
    return tail;
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return tail == null;
  }

  /** A copy of the slots, from oldest to newest. */
  List<Slot> snapshot() {
    List<Slot> out = new ArrayList<>(size);
    for (Slot s = tail; s != null; s = s.prev) out.add(s);
    Collections.reverse(out);
    return out;
  }

}
