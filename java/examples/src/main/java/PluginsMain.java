// Example of the context layer: plugins, services, events, config reload and tasks.
//
// The tree that is built:
//
//   app
//    ├─ server   requires db; listens to "request"; spawns a heartbeat task
//    ├─ db       ConfigPlugin<DbConfig>; provides db
//    └─ audit    hook "who": answers who handled the last request
//
// How to read the output:
//   - "server" is installed first but only starts once "db" provides the service.
//   - Changing db's config restarts db, so server is stopped first and started again with the new db.
//   - Disposing app stops everything in reverse order; server's task ends before server cleans up.

import context.ConfigHandle;
import context.ConfigPlugin;
import context.Context;
import context.EventKey;
import context.HookKey;
import context.Plugin;
import context.Root;
import context.ServiceKey;

public class PluginsMain {

  interface Db {
    String url();
  }

  record DbConfig(String url) {
  }

  static final ServiceKey<Db> DB = ServiceKey.of("db", Db.class);
  static final EventKey<String> REQUEST = EventKey.of("request", String.class);
  static final HookKey<String, String> WHO = HookKey.of("who", String.class, String.class);

  public static void main(String[] args) throws Exception {
    Root app = Context.root("app");

    app.plugin("server", Plugin.require(DB).then(ctx -> {
      Db db = ctx.get(DB);
      System.out.println("server: start on " + db.url());
      ctx.on(REQUEST, path -> System.out.println("server: " + path + " -> " + db.url()));
      ctx.spawn("heartbeat", signal -> {
        try {
          while (!signal.isCancelled()) Thread.sleep(1_000);
        } catch (InterruptedException e) {
          System.out.println("server: heartbeat stopped");
        }
        return null;
      });
      return () -> System.out.println("server: stop");
    }));

    ConfigPlugin<DbConfig> dbPlugin = (ctx, cfg) -> {
      System.out.println("db: connect " + cfg.url());
      ctx.provide(DB, cfg::url);
      return () -> System.out.println("db: close " + cfg.url());
    };
    ConfigHandle<DbConfig> db = app.plugin("db", dbPlugin, new DbConfig("pg://one"));

    app.plugin("audit", ctx -> {
      ctx.hook(WHO, path -> "server handled " + path);
      return null;
    });

    System.out.println("--- emit");
    app.emit(REQUEST, "/a");
    System.out.println("audit: " + app.bail(WHO, "/a").orElse("nobody"));

    System.out.println("--- change db config");
    db.update(new DbConfig("pg://two"));
    app.emit(REQUEST, "/b");

    System.out.println("--- dispose app");
    app.dispose();
  }

}
