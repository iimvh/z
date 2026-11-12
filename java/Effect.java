// "Một việc cần làm", trả về cách hoàn tác nó (có thể null nếu không cần hoàn tác).
// Tương ứng với `execute: () => Disposable` trong kernel JS.
public interface Effect {

  Disposable run();

}
