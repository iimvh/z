package core;

import java.util.List;

final class ErrorsTest {

  public static void main(String[] args) {
    run();
    Check.finish();
  }

  static void run() {
    Check.section("Component 1: Errors (error collection)");

    Errors empty = new Errors();
    empty.add("x", null);
    Check.ok("add(null) is ignored", empty.isEmpty());
    Check.ok("throwIfAny does not throw when there are no errors", Check.caught(empty::throwIfAny) == null);

    RuntimeException a = new RuntimeException("a");
    Errors one = new Errors();
    one.add("db", a);
    Throwable t1 = Check.caught(one::throwIfAny);
    if (t1 instanceof DisposeException d) {
      Check.ok("one error: the cause is the original error", d.getCause() == a);
      Check.eq("one error: keeps the source name", "db", d.failures().get(0).source());
    } else {
      Check.ok("one error -> DisposeException", false, t1);
    }

    RuntimeException b = new RuntimeException("b");
    RuntimeException c = new RuntimeException("c");
    Errors three = new Errors();
    three.add("a", a);
    three.add("b", b);
    three.add("c", c);
    Throwable t3 = Check.caught(three::throwIfAny);
    if (t3 instanceof DisposeException d) {
      Check.eq("several errors: correct order", List.of("a", "b", "c"),
          d.failures().stream().map(DisposeException.Failure::source).toList());
      Check.eq("several errors: the later ones are in suppressed", 2, d.getSuppressed().length);
      Check.ok("message contains the source name", d.getMessage().contains("[b]"), d.getMessage());
    } else {
      Check.ok("several errors -> DisposeException", false, t3);
    }
    Check.eq("user exceptions are NOT modified", 0, a.getSuppressed().length + b.getSuppressed().length);

    Errors many = new Errors();
    for (int i = 0; i < 150; i++) many.add("e" + i, new RuntimeException("e" + i));
    Throwable tm = Check.caught(many::throwIfAny);
    if (tm instanceof DisposeException d) {
      Check.ok("memory limit: keeps " + Errors.MAX_KEPT + ", counts the rest",
          d.failures().size() == Errors.MAX_KEPT && d.dropped() == 50 && d.total() == 150, d.total());
      Check.ok("message has a bounded length", d.getMessage().length() < 2000, d.getMessage().length());
    } else {
      Check.ok("several errors -> DisposeException", false, tm);
    }

    OutOfMemoryError oom = new OutOfMemoryError("simulated");
    Errors fatal = new Errors();
    fatal.add("a", a);
    fatal.add("oom", oom);
    fatal.add("b", b);
    Throwable tf = Check.caught(fatal::throwIfAny);
    Check.ok("a fatal error is rethrown unchanged", tf == oom, tf);
    Check.ok("the other errors are attached to the fatal error",
        oom.getSuppressed().length == 1 && oom.getSuppressed()[0] instanceof DisposeException d
            && d.total() == 2);

    StackOverflowError late = new StackOverflowError("arrives after the limit");
    Errors capped = new Errors();
    for (int i = 0; i < Errors.MAX_KEPT; i++) capped.add("e" + i, new RuntimeException());
    capped.add("soe", late);
    Check.ok("a fatal error still wins even when it arrives after the limit",
        Check.caught(capped::throwIfAny) == late);

    Errors intr = new Errors();
    intr.add("x", new InterruptedException());
    Check.caught(intr::throwIfAny);
    Check.ok("collecting an InterruptedException restores the interrupt flag", Thread.interrupted());

    Errors waited = new Errors();
    waited.noteInterrupted();
    Check.caught(waited::throwIfAny);
    Check.ok("an interrupted wait restores the flag (even with no errors)", Thread.interrupted());

    Errors evil = new Errors();
    evil.add("evil", new EvilToString());
    Throwable te = Check.caught(evil::throwIfAny);
    Check.ok("a throwing toString() does not break error reporting",
        te instanceof DisposeException && te.getMessage().contains("toString() threw"), te);

    Errors huge = new Errors();
    huge.add("huge", new RuntimeException("x".repeat(100_000)));
    Throwable th = Check.caught(huge::throwIfAny);
    Check.ok("a huge error message is truncated", th != null && th.getMessage().length() < 1000,
        th == null ? null : th.getMessage().length());

    RuntimeException primary = new RuntimeException("apply failed");
    Errors merge = new Errors();
    merge.add("child", c);
    Throwable merged = merge.mergeInto(primary);
    Check.ok("mergeInto: returns the primary error, with the others attached",
        merged == primary && primary.getSuppressed().length == 1
            && primary.getSuppressed()[0] instanceof DisposeException);

    RuntimeException primary2 = new RuntimeException("apply failed 2");
    OutOfMemoryError oom2 = new OutOfMemoryError("simulated 2");
    Errors mergeFatal = new Errors();
    mergeFatal.add("child", oom2);
    Check.ok("mergeInto: a fatal error still takes priority", mergeFatal.mergeInto(primary2) == oom2);

    Exception checked = new Exception("checked");
    Check.ok("sneakyThrow rethrows a checked error unchanged",
        Check.caught(() -> { throw Errors.sneakyThrow(checked); }) == checked);
  }

  static final class EvilToString extends RuntimeException {
    private static final long serialVersionUID = 1L;

    @Override
    public String toString() {
      throw new IllegalStateException("broken toString");
    }
  }

}
