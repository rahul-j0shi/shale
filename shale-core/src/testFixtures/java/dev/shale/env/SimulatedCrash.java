package dev.shale.env;

/**
 * Thrown by {@link FaultInjectionEnv} at the operation a test chose to crash at, and at every
 * operation after it. It is an {@link Error}, not an {@code IOException}, so no {@code catch
 * (IOException)} in the engine can swallow it and carry on as if the process were still alive.
 */
public final class SimulatedCrash extends Error {

  private static final long serialVersionUID = 1L;

  /** A crash described by {@code message}. */
  public SimulatedCrash(String message) {
    super(message);
  }
}
