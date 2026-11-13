rootProject.name = "z"

// core     : lifecycle kernel (Fiber...). No library dependencies.
// context  : plugins and services on top of core (provide / require). Depends only on core.
// examples : example program (Main) that uses core and context.
include("core", "context", "examples")
