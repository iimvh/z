package context;

import java.util.Objects;

/**
 * The typed name of a service. Two keys are the same service when both the name and the type match.
 *
 * For a generic service type (List&lt;String&gt;, ...) declare a small interface and use it as the type:
 * the key carries a Class, and a Class cannot express type arguments.
 */
public record ServiceKey<T>(String name, Class<T> type) {

  public ServiceKey {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    if (name.isBlank()) throw new IllegalArgumentException("service name must not be blank");
  }

  public static <T> ServiceKey<T> of(String name, Class<T> type) {
    return new ServiceKey<>(name, type);
  }

  /** Checks that impl really is a T; a mismatch is reported at provide() time, not when it is used. */
  T cast(Object impl) {
    if (!type.isInstance(impl)) {
      throw new ClassCastException("Service '" + name + "' must be a " + type.getName() + " but got "
          + (impl == null ? "null" : impl.getClass().getName()));
    }
    return type.cast(impl);
  }

  @Override
  public String toString() {
    return name + ":" + type.getSimpleName();
  }

}
