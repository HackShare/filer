package com.jivesoftware.os.filer.map.store;

import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.HeapByteBufferFactory;
import com.jivesoftware.os.filer.io.KeyMarshaller;
import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

public class ByteBufferObjectMapStoreTest {

    @Test (enabled = false)
    public void testMap() throws Exception {
        final int numIterations = 1_000;
        final int numFields = 10;
        final int numTerms = 100_000;
        //float loadFactor = Constants.DEFAULT_LOAD_FACTOR;
        Object[] obj = new Object[numFields * numTerms];
        for (int i = 0; i < obj.length; i++) {
            obj[i] = i;
        }

        for (int i = 0; i < numIterations; i++) {
            System.out.println("---------------------- " + i + " ----------------------");

            // bytebuffer mapstore setup
            BytesObjectMapStore<Long, Object> byteBufferObjectMapStore = new BytesObjectMapStore<>(8, false, 10, null, new HeapByteBufferFactory(), new KeyMarshaller<Long>() {

               @Override
                public byte[] keyBytes(Long key) {
                    return FilerIO.longBytes(key);
                }

                @Override
                public Long bytesKey(byte[] bytes, int offset) {
                    return FilerIO.bytesLong(bytes, offset);
                }
            });

            // bytebuffer mapstore insert
            long start = System.currentTimeMillis();
            for (int fieldId = 0; fieldId < numFields; fieldId++) {
                for (int termId = 0; termId < numTerms; termId++) {
                    long key = (long) termId << 32 | fieldId & 0xFFFF_FFFFL;
                    byteBufferObjectMapStore.add(key, obj[fieldId * numFields + termId]);
                }
            }
          

            // bytebuffer mapstore retrieve
            start = System.currentTimeMillis();
            for (int fieldId = 0; fieldId < numFields; fieldId++) {
                for (int termId = 0; termId < numTerms; termId++) {
                    long key = (long) termId << 32 | fieldId & 0xFFFF_FFFFL;
                    Object retrieved = byteBufferObjectMapStore.get(key);
                    assertTrue(retrieved == obj[fieldId * numFields + termId], "Failed at " + fieldId + ", " + termId);
                }
            }
            
        }
    }

}
