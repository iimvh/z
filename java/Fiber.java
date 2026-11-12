public class Fiber {

  // 2 thuộc tính
  private final Fiber parent; // null nếu là root
  private Node tail;          // nút thêm vào gần nhất; null nếu danh sách rỗng

  public Fiber(Fiber parent) {
    this.parent = parent;
  }

  public Fiber getParent() {
    return parent;
  }

  // Hàm 1 (kernel): chạy một việc, giữ lại cách hoàn tác nó.
  // Trả về một Handle: cầm nó và gọi dispose() thì việc này được hoàn tác.
  public Disposable effect(Effect execute) {
    Disposable undo = execute.run();

    Node node = new Node();
    node.undo = undo;
    append(node);

    return new Handle(this, node);
  }

  // Hàm 2: một component chỉ là một effect đệ quy.
  public Disposable use(Component component) {
    return effect(new UseEffect(this, component));
  }

  // Hàm 3: hoàn tác mọi thứ, theo thứ tự ngược (từ tail đi lên).
  public void dispose() {
    while (tail != null) {
      release(tail); // release() gỡ nút khỏi danh sách nên tail tự lùi về prev
    }
  }

  // ---- thao tác danh sách (nội bộ) ----

  private void append(Node node) {
    node.prev = tail;
    if (tail != null) tail.next = node;
    tail = node;
  }

  private void unlink(Node node) {
    if (node.prev != null) node.prev.next = node.next;
    if (node.next != null) node.next.prev = node.prev;
    else tail = node.prev; // node là tail
  }

  // Gỡ nút khỏi danh sách rồi mới chạy hoàn tác. Gọi lần hai thì không làm gì.
  private void release(Node node) {
    if (node.done) return;
    node.done = true;
    unlink(node);
    if (node.undo != null) node.undo.dispose();
  }

  // ---- các class nhỏ thay cho lambda ----

  // Thứ mà effect() trả về cho người gọi: nhớ "fiber nào" và "nút nào".
  private static class Handle implements Disposable {
    private final Fiber fiber;
    private final Node node;

    Handle(Fiber fiber, Node node) {
      this.fiber = fiber;
      this.node = node;
    }

    @Override
    public void dispose() {
      fiber.release(node);
    }
  }

  // Việc mà use() giao cho effect(): tạo fiber con, chạy component trong đó.
  private static class UseEffect implements Effect {
    private final Fiber parent;
    private final Component component;

    UseEffect(Fiber parent, Component component) {
      this.parent = parent;
      this.component = component;
    }

    @Override
    public Disposable run() {
      Fiber child = new Fiber(parent);
      Disposable undo = component.apply(child);
      return new ComponentUndo(child, undo);
    }
  }

  // Cách hoàn tác một component: dọn con trước, rồi mới dọn phần riêng của component.
  private static class ComponentUndo implements Disposable {
    private final Fiber child;
    private final Disposable undo; // có thể null

    ComponentUndo(Fiber child, Disposable undo) {
      this.child = child;
      this.undo = undo;
    }

    @Override
    public void dispose() {
      child.dispose();
      if (undo != null) undo.dispose();
    }
  }

}
