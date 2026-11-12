package core;

/**
 * The fiber has started disposing, so it rejects new work. Also thrown when the fiber is disposed
 * while a piece of work is still running: in that case that work's result has been or will be
 * cleaned up automatically, and it must not be used.
 */
public class FiberClosedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public FiberClosedException(String message) {
    super(message);
  }

}
