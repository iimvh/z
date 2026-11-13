package context;

/** Observes an event. Runs on the emitting thread; a long-running listener should watch ctx.signal(). */
@FunctionalInterface
public interface Listener<E> {

  void on(E event);

}
