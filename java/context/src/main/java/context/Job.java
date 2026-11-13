package context;

import core.Signal;

/**
 * Work that runs on its own (virtual) thread, started with Context.spawn(). It should stop early when
 * the signal is cancelled; blocking calls are also interrupted at that moment.
 */
@FunctionalInterface
public interface Job<T> {

  T run(Signal signal) throws Exception;

}
