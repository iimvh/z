package context;

/** Runs the whole context test suite: behaviour first, the concurrency stress test only if that passes. */
final class AllTests {

  public static void main(String[] args) {
    ContextTest.run();
    if (Check.failedCount() > 0) {
      System.out.println();
      System.out.println("Behaviour tests failed: skipping the stress test.");
      Check.finish();
    }
    ContextStressTest.run();
    Check.finish();
  }

}
