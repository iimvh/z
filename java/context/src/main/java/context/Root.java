package context;

import core.Disposable;
import core.Fiber;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** The top of a tree. The only context that can be disposed directly: disposing it disposes everything. */
public final class Root extends Context implements Disposable {

  Root(Registry reg, Fiber fiber, String name) {
    super(reg, fiber, name, Map.of());
  }

  @Override
  public void dispose() {
    fiber.dispose();
  }

  public boolean awaitDisposed(long timeout, TimeUnit unit) throws InterruptedException {
    return fiber.awaitDisposed(timeout, unit);
  }

  public boolean isDisposed() {
    return fiber.isDisposed();
  }

  /** The fiber tree, then the services and the plugins still waiting for one. */
  public String describe() {
    return fiber.describe() + reg.describe();
  }

}
