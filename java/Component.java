// Một component: nhận fiber của chính nó, trả về cách dọn dẹp riêng (có thể null).
// Tương ứng với `(fiber) => Disposable` trong kernel JS.
public interface Component {

  Disposable apply(Fiber fiber);

}
