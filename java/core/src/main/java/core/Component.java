package core;

/**
 * A component: receives ITS OWN fiber (a child fiber), uses it to create more work, and returns its
 * own cleanup (may be null). The component's own cleanup runs AFTER all work inside its child fiber
 * has been disposed.
 *
 * If apply() throws, the core automatically disposes everything that was created in the child fiber,
 * and then rethrows. A component only sees its own fiber: there is no path upward to the parent.
 */
@FunctionalInterface
public interface Component {

  Disposable apply(Fiber fiber);

}
