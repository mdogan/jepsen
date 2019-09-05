package jepsen.hazelcast_server;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.spi.merge.MergingValue;
import com.hazelcast.spi.merge.SplitBrainMergePolicy;

import java.io.IOException;
import java.util.TreeSet;

public class SetUnionMergePolicy implements SplitBrainMergePolicy<Object, MergingValue<Object>>, DataSerializable {

  @Override
  public Object merge(MergingValue<Object> mergingEntry, MergingValue<Object> existingEntry) {
    // Merge long arrays as sets
    final long[] a1;
    final long[] a2;
    if (null == mergingEntry.getDeserializedValue()) {
      a1 = new long[0];
    } else {
      a1 = (long[]) mergingEntry.getDeserializedValue();
    }
    if (null == existingEntry.getDeserializedValue()) {
      a2 = new long[0];
    } else {
      a2 = (long[]) existingEntry.getDeserializedValue();
    }

    // Merge arrays
    final TreeSet<Long> merged = new TreeSet<Long>();

    int i;
    for (i = 0; i < a1.length; i++) {
      merged.add(a1[i]);
    }
    for (i = 0; i < a2.length; i++) {
      merged.add(a2[i]);
    }

    System.out.println("MERGE RESULT: " + merged.toString());

    // Convert back to long array
    final long[] m = new long[merged.size()];
    i = 0;
    for (Long element : merged) {
      m[i] = element;
      i++;
    }

    return m;
  }

  @Override
  public void writeData(ObjectDataOutput out) throws IOException {
  }

  @Override
  public void readData(ObjectDataInput in) throws IOException {
  }
}
