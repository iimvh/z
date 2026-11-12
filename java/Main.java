// Ví dụ chạy kernel, không dùng lambda: mỗi việc là một class có tên.
//
// Cây được dựng ra:
//
//   root
//    ├─ Db
//    └─ Server
//        ├─ effect: RouteEffect
//        └─ ChildComponent
//
// Cách đọc output:
//   - Tạo theo thứ tự: db → server → route → child.
//   - Dọn theo thứ tự NGƯỢC: child → route → server (con trước, cha sau).
//   - Gỡ `server` không ảnh hưởng `db`, vì mỗi nhánh có danh sách hoàn tác riêng.
public class Main {

  public static void main(String[] args) {
    // Root: fiber không có cha (parent = null). Mọi thứ khác nằm dưới nó.
    Fiber root = new Fiber(null);

    // use() tạo một Fiber con cho Db, rồi gọi Db.apply(fiberCon).
    root.use(new Db());

    // Giữ handle trả về từ use() để tự gỡ riêng nhánh server sau này.
    Disposable server = root.use(new Server());

    // Gỡ riêng nhánh server: chạy child → route → server stop. db vẫn còn.
    System.out.println("--- dispose server only");
    server.dispose();

    // Gỡ toàn bộ cây còn lại: chỉ còn db.
    System.out.println("--- dispose root");
    root.dispose();
  }

  // ---- Db ----

  // Component: use() truyền vào `fiber` là fiber con của riêng Db. Db không dùng đến nó.
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

      // Một effect thuần (không phải component) nằm dưới fiber của server.
      fiber.effect(new RouteEffect());

      // Component lồng trong component: ChildComponent thuộc về fiber của server.
      fiber.use(new ChildComponent());

      // Phần dọn dẹp riêng của server. Chạy SAU khi các thứ nó tạo ra đã được dọn.
      return new ServerStop();
    }
  }

  static class ServerStop implements Disposable {
    @Override
    public void dispose() {
      System.out.println("server: stop");
    }
  }

  static class RouteEffect implements Effect {
    @Override
    public Disposable run() {
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
