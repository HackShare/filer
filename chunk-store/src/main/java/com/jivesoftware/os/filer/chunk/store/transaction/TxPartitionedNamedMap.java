/*
 * Copyright 2014 Jive Software.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jivesoftware.os.filer.chunk.store.transaction;

import com.jivesoftware.os.filer.io.PartitionFunction;
import com.jivesoftware.os.filer.io.api.ChunkTransaction;
import com.jivesoftware.os.filer.io.api.IndexAlignedChunkTransaction;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.filer.io.chunk.ChunkFiler;
import com.jivesoftware.os.filer.io.map.MapContext;
import com.jivesoftware.os.filer.io.map.MapStore;
import java.io.IOException;

/**
 * @author jonathan.colt
 */
public class TxPartitionedNamedMap {

    private final TxNamedMap[] namedMaps;
    private final PartitionFunction<byte[]> partitionFunction;

    public TxPartitionedNamedMap(PartitionFunction<byte[]> partitionFunction, TxNamedMap[] namedMaps) {
        this.namedMaps = namedMaps;
        this.partitionFunction = partitionFunction;
    }

    public boolean[] contains(byte[][] keysBytes, byte[] mapName, StackBuffer stackBuffer) throws IOException, InterruptedException {
        byte[][][] partitionedKeysBytes = new byte[namedMaps.length][keysBytes.length][];
        boolean[][] partitionedContains = new boolean[namedMaps.length][keysBytes.length];
        for (int i = 0; i < keysBytes.length; i++) {
            if (keysBytes[i] != null) {
                int p = partitionFunction.partition(namedMaps.length, keysBytes[i]);
                partitionedKeysBytes[p][i] = keysBytes[i];
            }
        }

        for (int p = 0; p < namedMaps.length; p++) {
            final byte[][] currentKeysBytes = partitionedKeysBytes[p];
            final boolean[] currentContains = partitionedContains[p];
            namedMaps[p].read(mapName, (context, filer, stackBuffer1, lock) -> {
                if (filer != null) {
                    synchronized (lock) {
                        for (int i = 0; i < currentKeysBytes.length; i++) {
                            byte[] keyBytes = currentKeysBytes[i];
                            currentContains[i] = (keyBytes != null && MapStore.INSTANCE.contains(filer, context, keyBytes, stackBuffer1));
                        }
                    }
                }
                return null;
            }, stackBuffer);
        }

        boolean[] result = new boolean[keysBytes.length];
        for (int i = 0; i < result.length; i++) {
            for (int p = 0; p < namedMaps.length; p++) {
                result[i] |= partitionedContains[p][i];
            }
        }
        return result;
    }

    public <R> R readWriteAutoGrow(byte[] partitionKey, final byte[] mapName, final ChunkTransaction<MapContext, R> mapTransaction, StackBuffer stackBuffer)
        throws IOException, InterruptedException {
        int i = partitionFunction.partition(namedMaps.length, partitionKey);
        final TxNamedMap namedMap = namedMaps[i];
        return namedMap.readWriteAutoGrow(mapName, 1, mapTransaction, stackBuffer);
    }

    public void multiReadWriteAutoGrow(byte[][] keysBytes,
        final byte[] mapName,
        final IndexAlignedChunkTransaction<MapContext, Void> indexAlignedChunkTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        byte[][][] partitionedKeysBytes = new byte[namedMaps.length][keysBytes.length][];
        int[] additionalCapacity = new int[namedMaps.length];
        for (int i = 0; i < keysBytes.length; i++) {
            if (keysBytes[i] != null) {
                int p = partitionFunction.partition(namedMaps.length, keysBytes[i]);
                partitionedKeysBytes[p][i] = keysBytes[i];
                additionalCapacity[p]++;
            }
        }

        for (int p = 0; p < namedMaps.length; p++) {
            final byte[][] currentKeysBytes = partitionedKeysBytes[p];
            namedMaps[p].readWriteAutoGrow(mapName, additionalCapacity[p], (context, filer, stackBuffer1, lock) -> {
                if (filer != null) {
                    for (int i = 0; i < currentKeysBytes.length; i++) {
                        byte[] keyBytes = currentKeysBytes[i];
                        if (keyBytes != null) {
                            indexAlignedChunkTransaction.commit(context, filer, stackBuffer1, lock, i);
                        }
                    }
                }
                return null;
            }, stackBuffer);
        }
    }

    public <R> R read(byte[] partitionKey, final byte[] mapName, final ChunkTransaction<MapContext, R> mapTransaction, StackBuffer stackBuffer) throws
        IOException, InterruptedException {
        int i = partitionFunction.partition(namedMaps.length, partitionKey);
        final TxNamedMap namedMap = namedMaps[i];
        return namedMap.read(mapName, mapTransaction, stackBuffer);
    }

    public Boolean stream(final byte[] mapName, final TxStream<byte[], MapContext, ChunkFiler> stream, StackBuffer stackBuffer) throws IOException,
        InterruptedException {
        for (TxNamedMap namedMap : namedMaps) {
            boolean result = namedMap.stream(mapName, stream, stackBuffer);
            if (!result) {
                return false;
            }
        }
        return true;
    }
}
