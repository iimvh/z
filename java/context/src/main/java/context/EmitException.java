package context;

import java.util.List;

/**
 * One or more listeners threw. When this is thrown, EVERY listener has already run: one failure does
 * not stop the others. failures(): each error with the name of the listener's registration, in order.
 */
public class EmitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** One error: `source` is the name of the listener registration that threw it. */
  public record Failure(String source, Throwable error) implements java.io.Serializable {}

  private static final int SHOWN = 5;

  @SuppressWarnings("serial") // List.copyOf returns an immutable list that can be serialized
  private final List<Failure> failures;

  EmitException(String event, List<Failure> failures) {
    super(summary(event, failures), failures.isEmpty() ? null : failures.get(0).error());
    this.failures = List.copyOf(failures);
    for (int i = 1; i < this.failures.size(); i++) addSuppressed(this.failures.get(i).error());
  }

  public List<Failure> failures() {
    return failures;
  }

  private static String summary(String event, List<Failure> failures) {
    StringBuilder sb = new StringBuilder("Event '").append(event).append("': ").append(failures.size())
        .append(" listener(s) failed");
    for (int i = 0; i < Math.min(failures.size(), SHOWN); i++) {
      Failure f = failures.get(i);
      sb.append(i == 0 ? ": " : "; ").append('[').append(f.source()).append("] ").append(text(f.error()));
    }
    if (failures.size() > SHOWN) sb.append("; ... and ").append(failures.size() - SHOWN).append(" more");
    return sb.toString();
  }

  private static String text(Throwable t) {
    try {
      String s = String.valueOf(t);
      return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    } catch (Throwable broken) {
      return t.getClass().getName() + " (toString() threw)";
    }
  }

}
