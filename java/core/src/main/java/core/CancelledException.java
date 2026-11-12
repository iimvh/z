package core;

/** Thrown by running work that detects a cancellation signal (Signal.throwIfCancelled). */
public class CancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CancelledException(String message) {
    super(message);
  }

}
