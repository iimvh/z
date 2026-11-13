package context;

import core.Fiber;
import core.FiberClosedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The event table of one tree (one Root).
 *
 * Each registration owns a fiber (its slot in the registering context). Every CALL of a listener runs
 * as an effect inside that fiber. This reuses the core guarantees instead of adding new locking:
 *  - unregistering disposes that fiber, and the core WAITS for calls still running on other threads
 *    (deadlock-safe: it defers instead when the call is on the same thread, or waiting would close a cycle);
 *  - once the fiber has started disposing, effect() is refused, so the listener is never called again.
 * So when a plugin's teardown passes its listener, that listener is not running and will never run again.
 *
 * Emitting takes a snapshot (copy-on-write), so listeners may register, unregister and emit freely.
 */
final class Bus {

  /** One listener or hook registration. */
  record Registration(String name, Fiber fiber, Object fn) {
  }

  private final Map<Object, CopyOnWriteArrayList<Registration>> table = new ConcurrentHashMap<>();

  void add(Object key, Registration r) {
    table.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(r);
  }

  void remove(Object key, Registration r) {
    table.computeIfPresent(key, (k, list) -> {
      list.remove(r);
      return list.isEmpty() ? null : list;
    });
  }

  int count(Object key) {
    List<Registration> list = table.get(key);
    return list == null ? 0 : list.size();
  }

  // ---------------- dispatch ----------------

  @SuppressWarnings("unchecked")
  <E> void emit(EventKey<E> key, E event) {
    E e = checked(key.type(), event, key);
    List<EmitException.Failure> failures = new ArrayList<>();
    for (Registration r : snapshot(key)) {
      Listener<E> l = (Listener<E>) r.fn();
      call(r, () -> {
        l.on(e);
        return null;
      }, failures);
    }
    if (!failures.isEmpty()) throw new EmitException(key.toString(), failures);
  }

  /** Asks handlers in registration order; stops at the first non-null answer. A failing handler throws at once. */
  @SuppressWarnings("unchecked")
  <E, R> Optional<R> bail(HookKey<E, R> key, E event) {
    E e = checked(key.type(), event, key);
    for (Registration r : snapshot(key)) {
      Hook<E, R> h = (Hook<E, R>) r.fn();
      List<EmitException.Failure> failures = new ArrayList<>(1);
      Object answer = call(r, () -> h.handle(e), failures);
      if (!failures.isEmpty()) throw new EmitException(key.toString(), failures);
      if (answer != null) return Optional.of(checkedResult(key, answer));
    }
    return Optional.empty();
  }

  /** Asks every handler; returns all non-null answers in registration order. Failures are gathered. */
  @SuppressWarnings("unchecked")
  <E, R> List<R> collect(HookKey<E, R> key, E event) {
    E e = checked(key.type(), event, key);
    List<R> answers = new ArrayList<>();
    List<EmitException.Failure> failures = new ArrayList<>();
    for (Registration r : snapshot(key)) {
      Hook<E, R> h = (Hook<E, R>) r.fn();
      Object answer = call(r, () -> h.handle(e), failures);
      if (answer != null) answers.add(checkedResult(key, answer));
    }
    if (!failures.isEmpty()) throw new EmitException(key.toString(), failures);
    return answers;
  }

  // ---------------- internals ----------------

  private List<Registration> snapshot(Object key) {
    List<Registration> list = table.get(key);
    return list == null ? List.of() : list; // CopyOnWriteArrayList iterates over a snapshot
  }

  private interface Call {
    Object run();
  }

  /**
   * Runs one call inside the registration's fiber. Returns the answer, or null if the call failed or
   * the registration is being removed (then it is simply skipped: it was unregistered).
   */
  private static Object call(Registration r, Call body, List<EmitException.Failure> failures) {
    Object[] out = new Object[1];
    try {
      r.fiber().effect(r.name(), signal -> {
        out[0] = body.run();
        return null;
      });
      return out[0];
    } catch (VirtualMachineError fatal) {
      throw fatal;
    } catch (FiberClosedException closed) {
      if (!r.fiber().isAlive()) return null;   // unregistered before or during the call: skip it
      failures.add(new EmitException.Failure(r.name(), closed));
      return null;
    } catch (Throwable t) {
      failures.add(new EmitException.Failure(r.name(), t));
      return null;
    }
  }

  private static <E> E checked(Class<E> type, Object event, Object key) {
    if (!type.isInstance(event)) {
      throw new ClassCastException("Event '" + key + "' expects a " + type.getName() + " but got "
          + (event == null ? "null" : event.getClass().getName()));
    }
    return type.cast(event);
  }

  private static <R> R checkedResult(HookKey<?, R> key, Object answer) {
    if (!key.resultType().isInstance(answer)) {
      throw new ClassCastException("Hook '" + key + "' must answer a " + key.resultType().getName() + " but got "
          + answer.getClass().getName());
    }
    return key.resultType().cast(answer);
  }

}
