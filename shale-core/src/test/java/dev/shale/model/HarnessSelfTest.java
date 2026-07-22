package dev.shale.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shale.Durability;
import dev.shale.StorageBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The harness must detect a divergence; a diff routine that never fails proves nothing. */
class HarnessSelfTest {

  @Test
  void assertMatches_detectsBackendThatIgnoresDeletes() {
    StorageBackend buggy = BuggyBackend.ignoringDeletes(new ReferenceBackend());
    ReferenceModel model = new ReferenceModel();
    byte[] key = {1};

    buggy.put(key, new byte[] {9}, Durability.NONE);
    model.put(key, new byte[] {9});
    buggy.delete(key, Durability.NONE); // buggy keeps the value...
    model.delete(key); // ...model removes it

    assertThatThrownBy(() -> Backends.assertMatches(buggy, model, List.of(key)))
        .isInstanceOf(AssertionError.class);
  }
}
