package core;

import java.util.List;

/**
 * One or more disposal operations threw. When this exception is thrown, ALL other disposal operations
 * have already run; the errors are reported at the end.
 *
 * failures(): each error together with the name of the work item (effect/component) that caused it,
 * in the order they occurred. getCause(): the first error. getSuppressed(): the remaining errors.
 */
public class DisposeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** One error: `source` is the name of the work item that caused it. */
  public record Failure(String source, Throwable error) implements java.io.Serializable {}

  private static final int SHOWN = 5;
  private static final int MAX_TEXT = 200;

  @SuppressWarnings("serial") // List.copyOf returns an immutable list that can be serialized
  private final List<Failure> failures;
  private final int dropped;

  DisposeException(List<Failure> failures, int dropped) {
    super(summary(failures, dropped), failures.isEmpty() ? null : failures.get(0).error());
    this.failures = List.copyOf(failures);
    this.dropped = dropped;
    for (int i = 1; i < this.failures.size(); i++) addSuppressed(this.failures.get(i).error());
  }

  /** The retained errors (at most Errors.MAX_KEPT). */
  public List<Failure> failures() {
    return failures;
  }

  /** The number of errors not retained because the limit was exceeded (counted only). */
  public int dropped() {
    return dropped;
  }

  public int total() {
    return failures.size() + dropped;
  }

  // The message has a bounded length and never throws: a user error's toString() may be broken or very long.
  private static String summary(List<Failure> failures, int dropped) {
    StringBuilder sb = new StringBuilder("Disposal failed with ").append(failures.size() + dropped).append(" error(s)");
    int shown = Math.min(failures.size(), SHOWN);
    for (int i = 0; i < shown; i++) {
      Failure f = failures.get(i);
      sb.append(i == 0 ? ": " : "; ")
          .append('[').append(cap(f.source())).append("] ")
          .append(cap(safeText(f.error())));
    }
    int more = failures.size() - shown + dropped;
    if (more > 0) sb.append("; ... and ").append(more).append(" more error(s)");
    return sb.toString();
  }

  static String safeText(Throwable t) {
    try {
      return String.valueOf(t);
    } catch (Throwable broken) {
      return t.getClass().getName() + " (toString() threw)";
    }
  }

  private static String cap(String s) {
    if (s == null) return "null";
    return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "...";
  }

}
