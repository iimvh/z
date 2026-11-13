package context;

/** A service is already provided: retract the current provider (dispose it) before providing another one. */
public class ServiceConflictException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  public ServiceConflictException(String message) {
    super(message);
  }

}
