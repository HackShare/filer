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

import com.jivesoftware.os.filer.chunk.store.ChunkFiler;
import com.jivesoftware.os.filer.chunk.store.ChunkStore;
import com.jivesoftware.os.filer.chunk.store.ChunkTransaction;
import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.GrowFiler;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.io.PartitionFunction;
import com.jivesoftware.os.filer.map.store.MapContext;
import java.io.IOException;

import static com.google.common.base.Preconditions.checkState;

/**
 * @author jonathan.colt
 */
public class TxNamedMap {

    private static final MapBackedKeyedFPIndexCreator[] POWER_CREATORS = new MapBackedKeyedFPIndexCreator[16];

    static {
        for (int i = 0; i < POWER_CREATORS.length; i++) {
            POWER_CREATORS[i] = new MapBackedKeyedFPIndexCreator(2, (int) FilerIO.chunkLength(i), true, 8, false);
        }
    }

    private final ChunkStore[] chunkStores;
    private final long constantFP;
    private final PartitionFunction<byte[]> partitionFunction;

    private final MapBackedKeyedFPIndexOpener opener = new MapBackedKeyedFPIndexOpener();
    private final CreateFiler<Integer, MapContext, ChunkFiler> mapCreator;
    private final OpenFiler<MapContext, ChunkFiler> mapOpener;
    private final GrowFiler<Integer, MapContext, ChunkFiler> mapGrower;

    public TxNamedMap(ChunkStore[] chunkStores,
        long constantFP,
        PartitionFunction<byte[]> partitionFunction,
        CreateFiler<Integer, MapContext, ChunkFiler> mapCreator,
        OpenFiler<MapContext, ChunkFiler> mapOpener,
        GrowFiler<Integer, MapContext, ChunkFiler> mapGrower) {
        this.chunkStores = chunkStores;
        this.constantFP = constantFP;
        this.partitionFunction = partitionFunction;
        this.mapCreator = mapCreator;
        this.mapOpener = mapOpener;
        this.mapGrower = mapGrower;

    }

    private final MapBackedKeyedFPIndexGrower grower = new MapBackedKeyedFPIndexGrower(1);

    public <R> R write(byte[] partitionKey, final byte[] mapName, final ChunkTransaction<MapContext, R> mapTransaction) throws IOException {
        int i = partitionFunction.partition(chunkStores.length, partitionKey);
        final ChunkStore chunkStore = chunkStores[i];
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                long fp = chunkStore.newChunk(null, KeyedFPIndexCreator.DEFAULT);
                checkState(fp == constantFP, "Must initialize to constantFP");
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, R>() {

            @Override
            public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer) throws IOException {

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                MapBackedKeyedFPIndexCreator creator = POWER_CREATORS[chunkPower];
                return monkey.commit(chunkStore, chunkPower, 1, creator, opener, grower, new ChunkTransaction<MapBackedKeyedFPIndex, R>() {

                    @Override
                    public R commit(MapBackedKeyedFPIndex monkey, ChunkFiler filer) throws IOException {
                        return monkey.commit(chunkStore, mapName, 1, mapCreator, mapOpener, mapGrower, mapTransaction);
                    }

                });

            }
        });
    }

    public <R> R read(byte[] partitionKey, final byte[] mapName, final ChunkTransaction<MapContext, R> mapTransaction) throws IOException {
        int i = partitionFunction.partition(chunkStores.length, partitionKey);
        final ChunkStore chunkStore = chunkStores[i];
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP)) {
                return mapTransaction.commit(null, null);
            }
        }
        return chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, R>() {

            @Override
            public R commit(PowerKeyedFPIndex monkey, ChunkFiler filer) throws IOException {

                int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                return monkey.commit(chunkStore, chunkPower, 1, null, opener, null, new ChunkTransaction<MapBackedKeyedFPIndex, R>() {

                    @Override
                    public R commit(MapBackedKeyedFPIndex monkey, ChunkFiler filer) throws IOException {
                        MapOpener opener = new MapOpener();
                        return monkey.commit(chunkStore, mapName, 1, null, opener, null, mapTransaction);
                    }

                });

            }
        });
    }

    public Boolean stream(final byte[] mapName, final TxStream<byte[], MapContext, ChunkFiler> stream) throws IOException {
        for (final ChunkStore chunkStore : chunkStores) {
            synchronized (chunkStore) {
                if (!chunkStore.isValid(constantFP)) {
                    continue;
                }
            }
            if (!chunkStore.execute(constantFP, KeyedFPIndexOpener.DEFAULT, new ChunkTransaction<PowerKeyedFPIndex, Boolean>() {

                @Override
                public Boolean commit(final PowerKeyedFPIndex monkey, ChunkFiler filer) throws IOException {

                    int chunkPower = FilerIO.chunkPower(mapName.length, 0);
                    return monkey.commit(chunkStore, chunkPower, null, null, opener, null, new ChunkTransaction<MapBackedKeyedFPIndex, Boolean>() {

                        @Override
                        public Boolean commit(final MapBackedKeyedFPIndex monkey, ChunkFiler filer) throws IOException {
                            return monkey.commit(chunkStore, mapName, null, null, new MapOpener(), null, new ChunkTransaction<MapContext, Boolean>() {

                                @Override
                                public Boolean commit(MapContext monkey, ChunkFiler filer) throws IOException {
                                    return stream.stream(mapName, monkey, filer);
                                }
                            });
                        }
                    });
                }
            })) {
                return false;
            }
        }
        return true;
    }
}