package context;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal test harness, no framework required. */
final class Check {

  private static final AtomicInteger passed = new AtomicInteger();
  private static final AtomicInteger failed = new AtomicInteger();

  interface Body {
    void run() throws Throwable;
  }

  static void section(String title) {
    System.out.println();
    System.out.println("== " + title);
  }

  static void ok(String name, boolean condition) {
    ok(name, condition, null);
  }

  static void ok(String name, boolean condition, Object detail) {
    if (condition) {
      passed.incrementAndGet();
      System.out.println("  ok   " + name);
    } else {
      failed.incrementAndGet();
      System.out.println("  FAIL " + name + (detail == null ? "" : "  -> " + detail));
    }
  }

  static void eq(String name, Object expected, Object actual) {
    ok(name, Objects.equals(expected, actual), "expected " + expected + " but got " + actual);
  }

  static Throwable caught(Body body) {
    try {
      body.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /** Runs body on a separate thread; reports FAIL if it hangs past the deadline or throws unexpectedly. */
  static void within(String name, long millis, Body body) {
    Throwable[] error = new Throwable[1];
    Thread t = new Thread(() -> {
      try {
        body.run();
      } catch (Throwable e) {
        error[0] = e;
      }
    }, "test:" + name);
    t.setDaemon(true);
    t.start();
    try {
      t.join(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (t.isAlive()) {
      failed.incrementAndGet();
      System.out.println("  FAIL " + name + "  -> HUNG for more than " + millis + " ms");
      for (StackTraceElement e : t.getStackTrace()) System.out.println("        at " + e);
    } else if (error[0] != null) {
      failed.incrementAndGet();
      System.out.println("  FAIL " + name + "  -> unexpected error: " + error[0]);
      error[0].printStackTrace(System.out);
    }
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  static int failedCount() {
    return failed.get();
  }

  static void finish() {
    System.out.println();
    System.out.println("Total: " + passed.get() + " ok, " + failed.get() + " FAIL");
    System.exit(failed.get() == 0 ? 0 : 1);
  }

}
