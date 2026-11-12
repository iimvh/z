// Layer 0-1: raw kernel. 2 properties, 3 functions. No Proxy, no magic.
export class Fiber {
  constructor(parent = null) {
    this.parent = parent       // property 1: pointer up the tree
    this.disposables = []      // property 2: undo list
  }

  // function 1 (kernel): run something, keep how to undo it
  effect(execute) {
    const undo = execute()
    let done = false
    const wrapper = () => {
      if (done) return
      done = true
      const i = this.disposables.indexOf(wrapper)
      if (i >= 0) this.disposables.splice(i, 1)
      return undo?.()
    }
    this.disposables.push(wrapper)
    return wrapper             // the return value is itself a disposable
  }

  // function 2: a component is just a recursive effect
  use(component, config) {
    return this.effect(() => {
      const child = new Fiber(this)
      const undo = component(child, config)
      return () => {
        child.dispose()        // children first
        undo?.()               // then the component's own cleanup
      }
    })
  }

  // function 3: undo everything, in reverse order
  dispose() {
    for (const d of this.disposables.slice().reverse()) d()
  }
}
