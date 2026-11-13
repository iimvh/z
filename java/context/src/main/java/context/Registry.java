package context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The service table of one tree (one Root), and the hosts that wait on it.
 *
 * Every decision "is this plugin running, may it start, must it stop" is made under ONE lock, together
 * with the change to the service table. This is what makes provide/retract and start/stop race-free:
 * a host either sees a service before it is retracted (and is then stopped by the retraction), or it
 * does not see it at all.
 *
 * Lock discipline (same rule as core): no Fiber call and no user code ever runs while holding `lock`.
 */
final class Registry {

  final Object lock = new Object();
  final ErrorHandler errors;
  final Bus bus = new Bus();

  private final Map<ServiceKey<?>, Provision> services = new LinkedHashMap<>();
  private final Map<ServiceKey<?>, Set<Host>> waiting = new HashMap<>();
  private final Set<Host> unconditional = new LinkedHashSet<>(); // hosts with no requirements
  private long seq;

  Registry(ErrorHandler errors) {
    this.errors = errors;
  }

  /** One provided service. Identity matters: retract only removes the provision it added. */
  record Provision(ServiceKey<?> key, Object impl, String owner) {
  }

  // ---------------- services ----------------

  void add(Provision p) {
    synchronized (lock) {
      Provision current = services.get(p.key());
      if (current != null) {
        throw new ServiceConflictException("Service '" + p.key() + "' is already provided by '" + current.owner()
            + "'; '" + p.owner() + "' cannot provide it until that provider is disposed");
      }
      services.put(p.key(), p);
    }
  }

  /** Starts every host that was waiting only for this key. Runs on the provider's thread, outside the lock. */
  void startDependents(ServiceKey<?> key) {
    List<Host> ready = new ArrayList<>();
    synchronized (lock) {
      Set<Host> hosts = waiting.get(key);
      if (hosts == null) return;
      for (Host h : hosts) {
        if (h.canStartLocked()) ready.add(h);
      }
    }
    for (Host h : ready) h.tryStart(false);
  }

  /**
   * Removes the service, then stops every running plugin that depends on it, newest first. When this
   * returns, those plugins have been disposed (or, if waiting would deadlock, will be as soon as the
   * blocking work finishes: the core guarantee).
   */
  void retract(Provision p) {
    List<Host.Activation> stopping = new ArrayList<>();
    synchronized (lock) {
      if (services.get(p.key()) != p) return;
      services.remove(p.key());
      Set<Host> hosts = waiting.get(p.key());
      if (hosts != null) {
        for (Host h : hosts) {
          Host.Activation a = h.detachLocked();
          if (a != null) stopping.add(a);
        }
      }
    }
    stopping.sort(Comparator.comparingLong((Host.Activation a) -> a.seq).reversed());
    Throwable first = null;
    for (Host.Activation a : stopping) {
      try {
        a.stop();
      } catch (Throwable t) {
        if (first == null) first = t;
        else if (first != t) first.addSuppressed(t);
      }
    }
    if (first != null) throw Host.sneakyThrow(first);
  }

  /** Called under lock: the required services, or null if one is missing. */
  Map<ServiceKey<?>, Object> snapshotLocked(List<ServiceKey<?>> keys) {
    Map<ServiceKey<?>, Object> deps = new HashMap<>();
    for (ServiceKey<?> k : keys) {
      Provision p = services.get(k);
      if (p == null) return null;
      deps.put(k, p.impl());
    }
    return Map.copyOf(deps);
  }

  long nextSeqLocked() {
    return ++seq;
  }

  // ---------------- hosts ----------------

  void watch(Host h) {
    synchronized (lock) {
      if (h.requires.isEmpty()) {
        unconditional.add(h);
        return;
      }
      for (ServiceKey<?> k : h.requires) waiting.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(h);
    }
  }

  void unwatch(Host h) {
    synchronized (lock) {
      h.forgetLocked();
      unconditional.remove(h);
      for (ServiceKey<?> k : h.requires) {
        Set<Host> hosts = waiting.get(k);
        if (hosts == null) continue;
        hosts.remove(h);
        if (hosts.isEmpty()) waiting.remove(k);
      }
    }
  }

  // ---------------- diagnostics ----------------

  String describe() {
    StringBuilder sb = new StringBuilder();
    synchronized (lock) {
      sb.append("services (").append(services.size()).append(")\n");
      for (Provision p : services.values()) {
        sb.append("  - ").append(p.key()).append(" by '").append(p.owner()).append("'\n");
      }
      List<String> blocked = new ArrayList<>();
      Set<Host> all = new LinkedHashSet<>();
      for (Set<Host> hs : waiting.values()) all.addAll(hs);
      for (Host h : all) {
        if (!h.isRunningLocked()) {
          List<String> missing = new ArrayList<>();
          for (ServiceKey<?> k : h.requires) if (!services.containsKey(k)) missing.add(k.toString());
          blocked.add("  - '" + h.name + "' missing " + missing + "\n");
        }
      }
      sb.append("waiting plugins (").append(blocked.size()).append(")\n");
      for (String line : blocked) sb.append(line);
    }
    return sb.toString();
  }

  boolean isProvided(ServiceKey<?> key) {
    synchronized (lock) {
      return services.containsKey(key);
    }
  }

}
