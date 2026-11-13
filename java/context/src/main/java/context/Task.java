package context;

import core.CancelledException;
import core.DeadlockException;
import core.Disposable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A running Job, returned by Context.spawn(). Disposing it cancels the job (signal + interrupt) and
 * returns only once the job has finished, like any other work in the tree.
 */
public final class Task<T> implements Disposable {

  private final CompletableFuture<T> result;
  private final Disposable handle;
  private final Thread thread;

  Task(CompletableFuture<T> result, Disposable handle, Thread thread) {
    this.result = result;
    this.handle = handle;
    this.thread = thread;
  }

  /**
   * Waits for the result. Throws the job's own exception unchanged, or CancelledException if the job
   * was cancelled (task or owning context disposed) before it could produce a result.
   */
  public T await() throws InterruptedException {
    selfWait();
    try {
      return result.get();
    } catch (ExecutionException e) {
      throw Host.sneakyThrow(e.getCause());
    }
  }

  public T await(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
    selfWait();
    try {
      return result.get(timeout, unit);
    } catch (ExecutionException e) {
      throw Host.sneakyThrow(e.getCause());
    }
  }

  public boolean isDone() {
    return result.isDone();
  }

  /** Cancels the job and waits until it has finished (the core waits for it, deadlock-safe). */
  @Override
  public void dispose() {
    handle.dispose();
  }

  private void selfWait() {
    if (Thread.currentThread() == thread) {
      throw new DeadlockException("Task '" + thread.getName() + "' is waiting for its own result");
    }
  }

  /** The result for a job that never produced one because it was cancelled. */
  static CancelledException cancelled(String name, Throwable cause) {
    CancelledException e = new CancelledException("Task '" + name + "' was cancelled");
    if (cause != null) e.initCause(cause);
    return e;
  }

}
