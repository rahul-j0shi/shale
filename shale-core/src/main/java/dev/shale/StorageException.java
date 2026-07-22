package dev.shale;

/** The environment failed: I/O error, disk full, missing file, permission denied. */
public final class StorageException extends ShaleException {

  private static final long serialVersionUID = 1L;

  public StorageException(String message) {
    super(message);
  }

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
