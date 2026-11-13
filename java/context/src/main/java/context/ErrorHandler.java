package context;

/**
 * Receives errors that have no caller to return to: a plugin that fails while being (re)applied because
 * a service appeared on some other thread's provide(). Errors with a caller are thrown to that caller.
 */
@FunctionalInterface
public interface ErrorHandler {

  void report(String source, Throwable error);

  /** Default: print to stderr, so nothing is lost silently. */
  ErrorHandler STDERR = (source, error) -> {
    System.err.println("[z] plugin '" + source + "' failed:");
    error.printStackTrace();
  };

}
