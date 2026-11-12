package core;

/** Runs the whole test suite: component tests first, composition tests after. */
final class AllTests {

  public static void main(String[] args) {
    ErrorsTest.run();
    SignalTest.run();
    UndoStackTest.run();
    LifecycleTest.run();
    WaitGraphTest.run();
    int componentFailures = Check.failedCount();
    if (componentFailures > 0) {
      System.out.println();
      System.out.println("Component tests failed: skipping the composition tests.");
      Check.finish();
    }
    FiberTest.run();
    StressTest.run();
    Check.finish();
  }

}
