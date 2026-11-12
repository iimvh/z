package core;

/**
 * A read-only cancellation signal. Running work uses it to learn "my fiber is being disposed, stop early".
 *
 * Guarantees:
 *  - Once cancelled, it never goes back to the not-cancelled state.
 *  - Each listener runs EXACTLY ONCE (unless it was unregistered before cancellation).
 *  - Registering after cancellation: the listener runs immediately on the calling thread, so it is never missed.
 *    In that case any error thrown by the listener propagates directly to the registering caller.
 *  - On cancellation: listeners run outside every core lock; their errors are collected into a
 *    DisposeException and do not stop other listeners.
 */
public interface Signal {

  boolean isCancelled();

  /** Registers work to do when cancelled. Returns a Disposable that unregisters it (safe to call more than once). */
  Disposable onCancel(Runnable listener);

  /** Throws CancelledException if already cancelled. */
  void throwIfCancelled();

}
