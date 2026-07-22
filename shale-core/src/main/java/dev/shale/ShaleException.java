package dev.shale;

/** Root of the engine's unchecked exception hierarchy (errors-and-logging.md §1). */
public abstract class ShaleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  protected ShaleException(String message) {
    super(message);
  }

  protected ShaleException(String message, Throwable cause) {
    super(message, cause);
  }
}
