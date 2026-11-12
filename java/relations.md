# Quan hệ giữa các class (seft-write)

```mermaid
classDiagram
  class Fiber {
    -Fiber parent
    -Node tail
    +effect(Effect) Disposable
    +use(Component) Disposable
    +dispose()
    -release(Node)
  }
  class Handle {
    -Fiber fiber
    -Node node
  }
  class UseEffect {
    -Fiber parent
    -Component component
  }
  class ComponentUndo {
    -Fiber child
    -Disposable undo
  }
  class Node {
    Disposable undo
    Node prev
    Node next
    boolean done
  }
  class Disposable {
    <<interface>>
    +dispose()
  }
  class Effect {
    <<interface>>
    +run() Disposable
  }
  class Component {
    <<interface>>
    +apply(Fiber) Disposable
  }

  Fiber --> Fiber : parent (cây)
  Fiber --> Node : tail (danh sách)
  Node --> Node : prev / next
  Node --> Disposable : undo
  Fiber ..> Handle : effect() trả về
  Fiber ..> UseEffect : use() tạo
  Handle ..|> Disposable
  Handle --> Fiber
  Handle --> Node
  UseEffect ..|> Effect
  UseEffect --> Fiber : parent
  UseEffect --> Component
  UseEffect ..> ComponentUndo : run() trả về
  ComponentUndo ..|> Disposable
  ComponentUndo --> Fiber : child
  ComponentUndo --> Disposable : undo
  Component ..> Fiber : nhận
  Component ..> Disposable : trả về
```

Mũi tên liền (`-->`) là field, mũi tên đứt (`..>`) là dùng tạm, `..|>` là implements.
`Handle`, `UseEffect`, `ComponentUndo` là class lồng (private static) trong `Fiber`, thay cho các lambda cũ.
