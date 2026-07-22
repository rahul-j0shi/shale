package dev.shale;

/** The engine cannot serve this request now: closed, failed, read-only, or stalled. */
public final class EngineStateException extends ShaleException {

  private static final long serialVersionUID = 1L;

  public EngineStateException(String message) {
    super(message);
  }

  public EngineStateException(String message, Throwable cause) {
    super(message, cause);
  }
}
