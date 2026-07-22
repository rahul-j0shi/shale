package dev.shale.model;

import java.security.SecureRandom;

/**
 * Sources the per-run test seed: {@code -Dshale.test.seed} if set, else a fresh random seed. The
 * value is echoed into the assertion-failure message by the caller (never on stdout — System.out is
 * banned), so every failure is reproducible (testing.md §2).
 */
final class Seeds {

  private Seeds() {}

  static long resolve() {
    String property = System.getProperty("shale.test.seed");
    return (property != null) ? Long.parseLong(property) : new SecureRandom().nextLong();
  }
}
