package core;

/**
 * A piece of work to run: runs it, then returns how to undo it (null if nothing needs undoing).
 *
 * The signal is cancelled when the fiber containing this work starts disposing. Long-running work
 * (network calls, waiting on a process, ...) should check the signal or register with onCancel to
 * stop early: while run() is still running, the teardown must wait for it to finish before continuing,
 * which keeps the ordering correct.
 *
 * Contract: if run() throws, it must clean up anything it opened before throwing. The core never
 * received an undo, so it cannot clean up on its behalf.
 */
@FunctionalInterface
public interface Effect {

  Disposable run(Signal signal);

}
