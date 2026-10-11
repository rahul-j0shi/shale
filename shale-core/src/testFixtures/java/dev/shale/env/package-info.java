/**
 * Test fixtures for the I/O seam: {@link dev.shale.env.FaultInjectionEnv}, which crashes the engine
 * at a chosen operation and simulates a power loss over a real directory (ADR-0012). Published to
 * the other modules' tests through Gradle's {@code java-test-fixtures}; never on a runtime
 * classpath.
 */
package dev.shale.env;
