package context;

import core.Disposable;

/** Returned by Context.plugin(): uninstalls the plugin, and tells whether it is currently running. */
public final class PluginHandle implements Disposable {

  private final Disposable handle;
  private final Host host;

  PluginHandle(Disposable handle, Host host) {
    this.handle = handle;
    this.host = host;
  }

  /** True while an instance is applied (all its services are present and apply() has returned). */
  public boolean isActive() {
    return host != null && host.isActive();
  }

  @Override
  public void dispose() {
    handle.dispose();
  }

}
