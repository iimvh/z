public class Node {

  Disposable undo; // cách hoàn tác do người dùng đưa vào (có thể null)
  Node prev;
  Node next;
  boolean done;    // đã bị gỡ chưa; đảm bảo chỉ gỡ và hoàn tác một lần

}
