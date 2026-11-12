package core;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects errors raised during teardown.
 *
 * Problem: an undo that throws must not stop the other undos, but its error must not disappear either.
 *
 * Failure conditions and how they are handled:
 *  - null error                         -> ignored; add() never throws.
 *  - Modifying the user's exceptions    -> errors are NOT attached as suppressed to the user's exception
 *    (e.g. a shared static exception)      during collection; that would contaminate it and leak memory.
 *    Only mergeInto() attaches collected errors, and only to a primary error the caller chooses.
 *  - Millions of errors exhaust memory  -> at most MAX_KEPT errors are kept; the rest are only counted.
 *  - A JVM fatal error is wrapped or    -> VirtualMachineError (OutOfMemoryError, StackOverflowError, ...)
 *    swallowed                             is always rethrown UNCHANGED and takes priority over other errors.
 *  - Interrupt flag lost                -> if an InterruptedException was collected, or a wait was interrupted,
 *                                          the flag is set again when the operation finishes.
 *  - toString() of a thrown error throws
 *    or is huge                         -> DisposeException builds a safe, length-limited message.
 *
 * Not thread-safe, by design: each teardown operation uses its own Errors instance; nothing is shared.
 */
final class Errors {

  static final int MAX_KEPT = 100;

  private final List<DisposeException.Failure> kept = new ArrayList<>();
  private int dropped;
  private Error fatal;
  private boolean interrupted;

  void add(String source, Throwable error) {
    if (error == null) return;
    if (error instanceof InterruptedException) interrupted = true;
    if (fatal == null && error instanceof VirtualMachineError) fatal = (Error) error;
    if (kept.size() < MAX_KEPT) {
      kept.add(new DisposeException.Failure(source == null ? "?" : source, error));
    } else {
      dropped++;
    }
  }

  /** A wait inside the core was interrupted: remember it so the flag is restored at the end. */
  void noteInterrupted() {
    interrupted = true;
  }

  boolean isEmpty() {
    return kept.isEmpty() && dropped == 0;
  }

  int count() {
    return kept.size() + dropped;
  }

  /**
   * Ends an operation: restores the interrupt flag, then throws if there were errors.
   * A fatal error is thrown unchanged (other errors are attached to it); otherwise a DisposeException is thrown.
   */
  void throwIfAny() {
    restoreInterrupt();
    if (fatal != null) {
      attachRest(fatal, fatal);
      throw fatal;
    }
    if (isEmpty()) return;
    throw new DisposeException(kept, dropped);
  }

  /**
   * A primary error already exists to be thrown (for example, the error from apply()): attaches all
   * collected errors to it as a suppressed DisposeException, and returns what should be thrown.
   * A fatal error still takes priority.
   */
  Throwable mergeInto(Throwable primary) {
    restoreInterrupt();
    if (fatal != null && fatal != primary) {
      suppress(fatal, primary);
      attachRest(fatal, primary);
      return fatal;
    }
    attachRest(primary, primary);
    return primary;
  }

  /** Throws any Throwable (including checked ones) without wrapping it. */
  @SuppressWarnings("unchecked")
  static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
    throw (T) t;
  }

  // ---- internals ----

  private void restoreInterrupt() {
    if (interrupted) Thread.currentThread().interrupt();
  }

  // Attaches the remaining errors (excluding target and skip) to target, wrapped in one DisposeException.
  private void attachRest(Throwable target, Throwable skip) {
    List<DisposeException.Failure> rest = new ArrayList<>();
    for (DisposeException.Failure f : kept) {
      if (f.error() != target && f.error() != skip) rest.add(f);
    }
    if (rest.isEmpty() && dropped == 0) return;
    suppress(target, new DisposeException(rest, dropped));
  }

  private static void suppress(Throwable target, Throwable extra) {
    if (target == extra) return;
    try {
      target.addSuppressed(extra);
    } catch (Throwable ignored) {
      // The target does not accept suppressed errors (suppression disabled): nothing can be done, and nothing may be thrown.
    }
  }

}
