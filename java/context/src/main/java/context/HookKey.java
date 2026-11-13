package context;

import java.util.Objects;

/**
 * The typed name of a hook: an event whose handlers may answer with a result of type R.
 * Handlers are registered with Context.hook(); callers ask with bail() (first answer) or collect() (all).
 */
public record HookKey<E, R>(String name, Class<E> type, Class<R> resultType) {

  public HookKey {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(resultType, "resultType");
    if (name.isBlank()) throw new IllegalArgumentException("hook name must not be blank");
  }

  public static <E, R> HookKey<E, R> of(String name, Class<E> type, Class<R> resultType) {
    return new HookKey<>(name, type, resultType);
  }

  @Override
  public String toString() {
    return name + ":" + type.getSimpleName() + "->" + resultType.getSimpleName();
  }

}
