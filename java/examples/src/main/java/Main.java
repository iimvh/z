// Example of running the kernel, without lambdas: each piece of work is a named class.
//
// The tree that is built:
//
//   root
//    ├─ Db
//    └─ Server
//        ├─ effect: RouteEffect
//        └─ ChildComponent
//
// How to read the output:
//   - Created in order: db -> server -> route -> child.
//   - Disposed in REVERSE order: child -> route -> server (children first, parent last).
//   - Disposing `server` does not affect `db`, because each branch has its own undo list.

import core.Component;
import core.Disposable;
import core.Effect;
import core.Fiber;
import core.Signal;

public class Main {

  public static void main(String[] args) {
    // Root: can only be created with Fiber.root(...). Everything else lives under it.
    Fiber root = Fiber.root("root");

    // use() creates a child Fiber for Db, then calls Db.apply(childFiber).
    root.use(new Db());

    // Keep the handle returned by use() so the server branch can be disposed on its own later.
    Disposable server = root.use(new Server());

    // Dispose only the server branch: runs child -> route -> server stop. db remains.
    System.out.println("--- dispose server only");
    server.dispose();

    // Dispose the rest of the tree: only db is left.
    System.out.println("--- dispose root");
    root.dispose();
  }

  // ---- Db ----

  // Component: use() passes `fiber`, a child fiber owned by Db. Db does not use it.
  static class Db implements Component {
    @Override
    public Disposable apply(Fiber fiber) {
      System.out.println("db: connect");
      return new DbClose();
    }
  }

  static class DbClose implements Disposable {
    @Override
    public void dispose() {
      System.out.println("db: close");
    }
  }

  // ---- Server ----

  static class Server implements Component {
    @Override
    public Disposable apply(Fiber fiber) {
      System.out.println("server: listen");

      // A plain effect (not a component) lives under the server's fiber.
      fiber.effect(new RouteEffect());

      // A component nested inside a component: ChildComponent belongs to the server's fiber.
      fiber.use(new ChildComponent());

      // The server's own cleanup. Runs AFTER the things it created have been disposed.
      return new ServerStop();
    }
  }

  static class ServerStop implements Disposable {
    @Override
    public void dispose() {
      System.out.println("server: stop");
    }
  }

  // signal: cancelled when the fiber containing this effect starts disposing. Not needed for such a quick effect.
  static class RouteEffect implements Effect {
    @Override
    public Disposable run(Signal signal) {
      System.out.println("server: add route /a");
      return new RouteRemove();
    }
  }

  static class RouteRemove implements Disposable {
    @Override
    public void dispose() {
      System.out.println("server: remove route /a");
    }
  }

  static class ChildComponent implements Component {
    @Override
    public Disposable apply(Fiber fiber) {
      System.out.println("  child: start");
      return new ChildStop();
    }
  }

  static class ChildStop implements Disposable {
    @Override
    public void dispose() {
      System.out.println("  child: stop");
    }
  }

}
