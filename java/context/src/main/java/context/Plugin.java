package context;

import core.Disposable;
import java.util.List;
import java.util.Objects;

/**
 * A plugin: like a core Component, but it lives in a Context and may depend on services.
 *
 * The plugin is applied only while EVERY service in requires() is provided. When one of them is
 * retracted, the plugin's whole branch is disposed (in reverse order, as in core); when they are all
 * provided again, the plugin is applied again, in a fresh context. Inside apply(), ctx.get(key) returns
 * a required service and stays valid for the life of that context.
 *
 * Contract: like Effect/Component in core. If apply() throws, everything it created is disposed.
 */
@FunctionalInterface
public interface Plugin {

  Disposable apply(Context ctx);

  /** Services that must be present for this plugin to run. Empty by default. */
  default List<ServiceKey<?>> requires() {
    return List.of();
  }

  /** Starts declaring a plugin that depends on services: {@code Plugin.require(DB).then(ctx -> ...)}. */
  static Requirement require(ServiceKey<?>... keys) {
    return new Requirement(List.of(keys));
  }

  /** The services half of a plugin declared with {@link Plugin#require}. */
  final class Requirement {
    private final List<ServiceKey<?>> keys;

    private Requirement(List<ServiceKey<?>> keys) {
      this.keys = keys;
    }

    public Plugin then(Plugin body) {
      Objects.requireNonNull(body, "body");
      return new Requiring(keys, body);
    }
  }

  /** A body plus its requirements. Keeps the body's class for naming. */
  record Requiring(List<ServiceKey<?>> requires, Plugin body) implements Plugin {
    @Override
    public Disposable apply(Context ctx) {
      return body.apply(ctx);
    }
  }

}
