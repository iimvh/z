package core;

/** An explicit wait (awaitDisposed) that can never complete is refused instead of hanging forever. */
public class DeadlockException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public DeadlockException(String message) {
    super(message);
  }

}
