package context;

/** Handles a hook: returns an answer, or null to let the next handler answer. */
@FunctionalInterface
public interface Hook<E, R> {

  R handle(E event);

}
