package context;

import java.util.Objects;

/**
 * The typed name of an event that listeners observe with Context.on() and that anyone can emit().
 * Two keys are the same event when both the name and the type match.
 */
public record EventKey<E>(String name, Class<E> type) {

  public EventKey {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (name.isBlank()) throw new IllegalArgumentException("event name must not be blank");
  }

  public static <E> EventKey<E> of(String name, Class<E> type) {
    return new EventKey<>(name, type);
  }

  @Override
  public String toString() {
    return name + ":" + type.getSimpleName();
  }

}
