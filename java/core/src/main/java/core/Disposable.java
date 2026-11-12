package core;

/**
 * How to undo one piece of work.
 *
 * A fiber guarantees that it calls dispose() of each work item it manages at most ONCE, and always
 * calls it outside every core lock, so dispose() may call back into a Fiber without causing a deadlock.
 */
@FunctionalInterface
public interface Disposable {

  void dispose();

}
