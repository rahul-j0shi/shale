package dev.shale.model;

import dev.shale.BytewiseComparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** In-memory reference: the sorted-map semantics the engine must reproduce (testing.md §1). */
final class ReferenceModel {

  private final NavigableMap<byte[], byte[]> map =
      new TreeMap<>((a, b) -> BytewiseComparator.INSTANCE.compare(a, b));

  void put(byte[] userKey, byte[] value) {
    map.put(userKey.clone(), value.clone());
  }

  void delete(byte[] userKey) {
    map.remove(userKey);
  }

  byte[] get(byte[] userKey) {
    byte[] value = map.get(userKey);
    return value == null ? null : value.clone();
  }

  /** Entries in {@code [from, to)} (null bound = open-ended), ascending, as [key, value] pairs. */
  List<byte[][]> entries(byte[] fromInclusive, byte[] toExclusive) {
    NavigableMap<byte[], byte[]> view = map;
    if (fromInclusive != null) {
      view = view.tailMap(fromInclusive, true);
    }
    if (toExclusive != null) {
      view = view.headMap(toExclusive, false);
    }
    List<byte[][]> out = new ArrayList<>();
    for (Map.Entry<byte[], byte[]> entry : view.entrySet()) {
      out.add(new byte[][] {entry.getKey().clone(), entry.getValue().clone()});
    }
    return out;
  }
}
