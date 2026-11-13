package context;

import core.Disposable;
import java.util.Objects;

/** Returned by Context.plugin(name, plugin, config): uninstalls the plugin, or changes its config. */
public final class ConfigHandle<C> implements Disposable {

  private final PluginHandle handle;
  private final Host host;

  ConfigHandle(PluginHandle handle, Host host) {
    this.handle = handle;
    this.host = host;
  }

  /** The latest config (the one the running instance has, or is about to have). */
  @SuppressWarnings("unchecked")
  public C config() {
    return (C) host.config();
  }

  /**
   * Changes the config. Equal to the current one (equals()): nothing happens. Otherwise, when this
   * returns, the running instance has the new config, in place or restarted; if the plugin is waiting
   * for services, the config is only recorded and used when it starts.
   *
   * A failure of the restarted apply() (or of reconfigure()) is thrown here; the plugin is then not
   * running, and starts again (with this config) the next time update() or its services change.
   * If another thread is updating this plugin at the same time, this call records the config and
   * returns; that thread applies it before it returns.
   */
  public void update(C config) {
    Objects.requireNonNull(config, "config");
    host.update(config);
  }

  public boolean isActive() {
    return handle.isActive();
  }

  @Override
  public void dispose() {
    handle.dispose();
  }

}
