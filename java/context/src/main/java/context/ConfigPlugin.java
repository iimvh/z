package context;

import core.Disposable;
import java.util.List;
import java.util.Objects;

/**
 * A plugin that takes a config. Installed with Context.plugin(name, plugin, config); the returned
 * ConfigHandle changes the config later, at runtime.
 *
 * On a config change (by equals()), only THIS plugin's branch is affected:
 *  - reconfigure() is offered the change first, in place. Return true if it was handled.
 *  - otherwise (the default) the instance is disposed and apply() runs again with the new config.
 * The rest of the tree is untouched, except what depends on services this plugin provides: those
 * follow the usual service rules (stopped when the service is retracted, started again after).
 *
 * Configs should be immutable values (records are ideal): they are compared with equals() and shared.
 */
public interface ConfigPlugin<C> {

  Disposable apply(Context ctx, C config);

  /** Services that must be present for this plugin to run. Empty by default. */
  default List<ServiceKey<?>> requires() {
    return List.of();
  }

  /**
   * Applies a config change to the running instance without restarting it. Runs inside the instance's
   * fiber (so a concurrent disposal waits for it). Return false to restart instead. If it throws, the
   * instance is disposed (it may be half-updated) and the error is thrown to the update() caller.
   */
  default boolean reconfigure(Context ctx, C previous, C next) {
    return false;
  }

  /** Starts declaring a configured plugin that depends on services. */
  static Requirement require(ServiceKey<?>... keys) {
    return new Requirement(List.of(keys));
  }

  /** The services half of a configured plugin declared with {@link ConfigPlugin#require}. */
  final class Requirement {
    private final List<ServiceKey<?>> keys;

    private Requirement(List<ServiceKey<?>> keys) {
      this.keys = keys;
    }

    public <C> ConfigPlugin<C> then(ConfigPlugin<C> body) {
      Objects.requireNonNull(body, "body");
      return new Requiring<>(keys, body);
    }
  }

  /** A body plus its requirements. Keeps the body's class for naming and its reconfigure(). */
  record Requiring<C>(List<ServiceKey<?>> requires, ConfigPlugin<C> body) implements ConfigPlugin<C> {
    @Override
    public Disposable apply(Context ctx, C config) {
      return body.apply(ctx, config);
    }

    @Override
    public boolean reconfigure(Context ctx, C previous, C next) {
      return body.reconfigure(ctx, previous, next);
    }
  }

}
