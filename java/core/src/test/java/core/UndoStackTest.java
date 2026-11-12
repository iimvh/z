package core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class UndoStackTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static Slot slot(String name) {
    return new Slot(name, null, new CancelSource(name), Thread.currentThread());
  }

  static void run() {
    Check.section("Component 3: Slot (state machine) + UndoStack (undo list)");

    // ---- Slot ----
    Thread me = Thread.currentThread();
    Thread other = new Thread(() -> { });

    Slot a = slot("a");
    a.activate(() -> { });
    a.beginRelease(me);
    a.finishRelease(me);
    Check.ok("valid path: PENDING -> ACTIVE -> RELEASING -> RELEASED",
        a.state() == Slot.State.RELEASED && a.owner() == null && a.undo() == null);

    Slot f = slot("f");
    f.fail();
    Check.ok("PENDING -> FAILED", f.state() == Slot.State.FAILED && f.isSettled());

    Slot e = slot("e");
    e.settleEmpty();
    Check.ok("PENDING -> RELEASED (nothing to undo)", e.state() == Slot.State.RELEASED);

    Slot r = slot("r");
    r.activate(() -> { });
    r.beginRelease(me);
    r.revert(me);
    Check.ok("RELEASING -> ACTIVE when deferred, with the release request recorded",
        r.state() == Slot.State.ACTIVE && r.releaseRequested() && r.owner() == null);

    Slot x = slot("x");
    x.activate(() -> { });
    Check.ok("activate twice -> throws", Check.caught(() -> x.activate(null)) instanceof IllegalStateException);
    Check.ok("fail while ACTIVE -> throws", Check.caught(x::fail) instanceof IllegalStateException);
    Slot p = slot("p");
    Check.ok("beginRelease while still PENDING -> throws",
        Check.caught(() -> p.beginRelease(me)) instanceof IllegalStateException);
    x.beginRelease(me);
    Check.ok("finishRelease by a thread that is not the owner -> throws",
        Check.caught(() -> x.finishRelease(other)) instanceof IllegalStateException);
    Check.ok("revert by a thread that is not the owner -> throws",
        Check.caught(() -> x.revert(other)) instanceof IllegalStateException);
    Check.ok("beginRelease twice -> throws",
        Check.caught(() -> x.beginRelease(me)) instanceof IllegalStateException);
    x.finishRelease(me);
    Check.ok("finishRelease twice -> throws",
        Check.caught(() -> x.finishRelease(me)) instanceof IllegalStateException);

    // ---- UndoStack ----
    UndoStack st = new UndoStack();
    Slot s1 = slot("1");
    Slot s2 = slot("2");
    Slot s3 = slot("3");
    st.push(s1);
    st.push(s2);
    st.push(s3);
    Check.ok("LIFO: the last element is the newest", st.last() == s3 && st.size() == 3);
    Check.eq("snapshot from oldest to newest", List.of("1", "2", "3"), names(st.snapshot()));

    st.remove(s2);
    Check.ok("removing a middle element: its links are fully cleared",
        s2.prev == null && s2.next == null && s2.list == null);
    Check.ok("removing a middle element: the neighbours are relinked correctly",
        s3.prev == s1 && s1.next == s3 && st.size() == 2);

    Check.ok("remove twice -> throws", Check.caught(() -> st.remove(s2)) instanceof IllegalStateException);
    Check.ok("add twice -> throws", Check.caught(() -> st.push(s3)) instanceof IllegalStateException);
    UndoStack other2 = new UndoStack();
    Check.ok("removing a slot from a different list -> throws, and does not corrupt the list",
        Check.caught(() -> other2.remove(s1)) instanceof IllegalStateException && st.size() == 2);
    Check.ok("adding a slot that belongs to another list -> throws",
        Check.caught(() -> other2.push(s1)) instanceof IllegalStateException);

    st.remove(s3);
    st.remove(s1);
    Check.ok("all removed: empty", st.isEmpty() && st.last() == null && st.size() == 0);

    Random rnd = new Random(42);
    UndoStack big = new UndoStack();
    List<Slot> live = new ArrayList<>();
    for (int i = 0; i < 1_000_000; i++) {
      if (live.isEmpty() || rnd.nextInt(3) != 0) {
        Slot s = slot("s");
        big.push(s);
        live.add(s);
      } else {
        Slot s = live.remove(rnd.nextInt(live.size()));
        big.remove(s);
      }
    }
    int counted = 0;
    for (Slot s = big.last(); s != null; s = s.prev) counted++;
    Check.ok("1,000,000 random operations: size matches the number of nodes actually reachable",
        counted == big.size() && counted == live.size(), counted + " / " + big.size() + " / " + live.size());
  }

  static List<String> names(List<Slot> slots) {
    List<String> out = new ArrayList<>();
    for (Slot s : slots) out.add(s.name);
    return out;
  }

}
