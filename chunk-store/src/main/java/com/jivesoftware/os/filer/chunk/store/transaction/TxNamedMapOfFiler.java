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

import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.GrowFiler;
import com.jivesoftware.os.filer.io.NoOpCreateFiler;
import com.jivesoftware.os.filer.io.NoOpOpenFiler;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.io.api.ChunkTransaction;
import com.jivesoftware.os.filer.io.api.HintAndTransaction;
import com.jivesoftware.os.filer.io.api.IndexAlignedChunkTransaction;
import com.jivesoftware.os.filer.io.api.IndexAlignedHintAndTransactionSupplier;
import com.jivesoftware.os.filer.io.api.KeyRange;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.filer.io.chunk.ChunkFiler;
import com.jivesoftware.os.filer.io.chunk.ChunkStore;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @param <N>
 * @param <H>
 * @param <M>
 * @author jonathan.colt
 */
public class TxNamedMapOfFiler<N extends FPIndex<byte[], N>, H, M> {

    public static final NoOpCreateFiler<ChunkFiler> CHUNK_FILER_CREATOR = new NoOpCreateFiler<>();
    public static final NoOpOpenFiler<ChunkFiler> CHUNK_FILER_OPENER = new NoOpOpenFiler<>();
    public static final TxNamedMapOfFilerOverwriteGrowerProvider<Long, Void> OVERWRITE_GROWER_PROVIDER =
        sizeHint -> new GrowFiler<Long, Void, ChunkFiler>() {

            @Override
            public Long acquire(Long sizeHint, Void monkey, ChunkFiler filer, Object lock) throws IOException {
                return filer.length() < sizeHint ? sizeHint : null;
            }

            @Override
            public void growAndAcquire(Long sizeHint,
                Void currentMonkey,
                ChunkFiler currentFiler,
                Void newMonkey,
                ChunkFiler newFiler,
                Object currentLock,
                Object newLock,
                StackBuffer stackBuffer
            ) throws IOException {
                synchronized (currentLock) {
                    synchronized (newLock) {
                        currentFiler.seek(0);
                        newFiler.seek(0);
                        FilerIO.copy(currentFiler, newFiler, -1);
                    }
                }
            }

            @Override
            public void release(Long sizeHint, Void monkey, Object lock) {
            }
        };

    public static final TxNamedMapOfFilerRewriteGrowerProvider<Long, Void> REWRITE_GROWER_PROVIDER = new TxNamedMapOfFilerRewriteGrowerProvider<Long, Void>() {

        @Override
        public <R> GrowFiler<Long, Void, ChunkFiler> create(final Long sizeHint,
            final ChunkTransaction<Void, R> chunkTransaction,
            final AtomicReference<R> result) {

            return new GrowFiler<Long, Void, ChunkFiler>() {

                @Override
                public Long acquire(Long sizeHint, Void monkey, ChunkFiler filer, Object lock) throws IOException {
                    return sizeHint;
                }

                @Override
                public void growAndAcquire(Long sizeHint,
                    Void currentMonkey,
                    ChunkFiler currentFiler,
                    Void newMonkey,
                    ChunkFiler newFiler,
                    Object currentLock,
                    Object newLock,
                    StackBuffer stackBuffer) throws IOException, InterruptedException {
                    result.set(chunkTransaction.commit(newMonkey, newFiler, stackBuffer, newLock));
                }

                @Override
                public void release(Long sizeHint, Void monkey, Object lock) {
                }
            };
        }
    };

    private final TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> skyhookCog;
    private final ChunkStore chunkStore;
    private final long constantFP;

    private final CreateFiler<Void, PowerKeyedFPIndex, ChunkFiler> skyHookIndexCreator;
    private final OpenFiler<PowerKeyedFPIndex, ChunkFiler> skyHookIndexOpener;
    private final CreateFiler<Void, PowerKeyedFPIndex, ChunkFiler> namedIndexCreator;
    private final OpenFiler<PowerKeyedFPIndex, ChunkFiler> namedIndexOpener;
    private final CreateFiler<Integer, N, ChunkFiler>[] namedPowerCreator;
    private final OpenFiler<N, ChunkFiler> namedPowerOpener;
    private final GrowFiler<Integer, N, ChunkFiler> namedPowerGrower;
    private final CreateFiler<H, M, ChunkFiler> filerCreator;
    private final OpenFiler<M, ChunkFiler> filerOpener;
    private final TxNamedMapOfFilerOverwriteGrowerProvider<H, M> overwriteGrowerProvider;
    private final TxNamedMapOfFilerRewriteGrowerProvider<H, M> rewriteGrowerProvider;

    public TxNamedMapOfFiler(
        TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> skyhookCog,
        int seed,
        ChunkStore chunkStore,
        CreateFiler<Integer, N, ChunkFiler>[] namedPowerCreator,
        OpenFiler<N, ChunkFiler> namedPowerOpener,
        GrowFiler<Integer, N, ChunkFiler> namedPowerGrower,
        CreateFiler<H, M, ChunkFiler> filerCreator,
        OpenFiler<M, ChunkFiler> filerOpener,
        IntIndexSemaphore skyHookKeySemaphores,
        IntIndexSemaphore namedKeySemaphores,
        TxNamedMapOfFilerOverwriteGrowerProvider<H, M> overwriteGrowerProvider,
        TxNamedMapOfFilerRewriteGrowerProvider<H, M> rewriteGrowerProvider) {
        this.skyhookCog = skyhookCog;
        this.chunkStore = chunkStore;
        this.constantFP = chunkStore.getSkyHookFp();

        this.skyHookIndexCreator = new KeyedFPIndexCreator(seed, KeyedFPIndexCreator.DEFAULT_MAGIC_HEADER, KeyedFPIndexCreator.DEFAULT_MAX_KEY_SIZE_POWER,
            skyHookKeySemaphores);
        this.skyHookIndexOpener = new KeyedFPIndexOpener(seed, KeyedFPIndexCreator.DEFAULT_MAGIC_HEADER, KeyedFPIndexCreator.DEFAULT_MAX_KEY_SIZE_POWER,
            skyHookKeySemaphores);

        this.namedIndexCreator = new KeyedFPIndexCreator(seed, KeyedFPIndexCreator.DEFAULT_MAGIC_HEADER, KeyedFPIndexCreator.DEFAULT_MAX_KEY_SIZE_POWER,
            namedKeySemaphores);
        this.namedIndexOpener = new KeyedFPIndexOpener(seed, KeyedFPIndexCreator.DEFAULT_MAGIC_HEADER, KeyedFPIndexCreator.DEFAULT_MAX_KEY_SIZE_POWER,
            namedKeySemaphores);

        this.namedPowerCreator = namedPowerCreator;
        this.namedPowerOpener = namedPowerOpener;
        this.namedPowerGrower = namedPowerGrower;
        this.filerCreator = filerCreator;
        this.filerOpener = filerOpener;
        this.overwriteGrowerProvider = overwriteGrowerProvider;
        this.rewriteGrowerProvider = rewriteGrowerProvider;
    }

    public <R> R readWriteAutoGrow(final byte[] mapName,
        final byte[] filerKey,
        final H sizeHint,
        final ChunkTransaction<M, R> filerTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                chunkStore.newChunk(null, skyHookIndexCreator, stackBuffer);
            }
        }
        return chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.readWriteAutoGrow(chunkStore, chunkPower, 2, skyhookCog.creators[chunkPower], skyhookCog.opener, skyhookCog.grower,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> skyHookMonkey.readWriteAutoGrow(chunkStore,
                    mapName, null, namedIndexCreator, namedIndexOpener, null,
                    (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                        int chunkPower1 = FilerIO.chunkPower(filerKey.length, 0);
                        return namedIndexMonkey.readWriteAutoGrow(chunkStore, chunkPower1, 2, namedPowerCreator[chunkPower1], namedPowerOpener,
                            namedPowerGrower,
                            (namedPowerMonkey, namedPowerFiler, stackBuffer4, namedPowerLock) -> {
                                // TODO consider using the provided filer in appropriate cases.
                                GrowFiler<H, M, ChunkFiler> overwriteGrower = overwriteGrowerProvider.create(sizeHint);
                                return namedPowerMonkey.readWriteAutoGrow(chunkStore, filerKey, sizeHint, filerCreator, filerOpener,
                                    overwriteGrower, filerTransaction, stackBuffer4);
                            }, stackBuffer3);
                    }, stackBuffer2), stackBuffer1);
        }, stackBuffer);
    }

    public <R> R writeNewReplace(final byte[] mapName,
        final byte[] filerKey,
        final H sizeHint,
        final ChunkTransaction<M, R> chunkTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                chunkStore.newChunk(null, skyHookIndexCreator, stackBuffer);
            }
        }
        return chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.readWriteAutoGrow(chunkStore, chunkPower, 2, skyhookCog.creators[chunkPower], skyhookCog.opener,
                skyhookCog.grower,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> skyHookMonkey.readWriteAutoGrow(chunkStore,
                    mapName, null, namedIndexCreator, namedIndexOpener, null,
                    (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                        int chunkPower1 = FilerIO.chunkPower(filerKey.length, 0);
                        return namedIndexMonkey.readWriteAutoGrow(chunkStore, chunkPower1, 1, namedPowerCreator[chunkPower1], namedPowerOpener,
                            namedPowerGrower,
                            (namedPowerMonkey, namedPowerFiler, stackBuffer4, namedPowerLock) -> {
                                // TODO consider using the provided filer in appropriate cases.
                                final AtomicReference<R> result = new AtomicReference<>();
                                GrowFiler<H, M, ChunkFiler> rewriteGrower = rewriteGrowerProvider.create(sizeHint,
                                    chunkTransaction, result);
                                return namedPowerMonkey.writeNewReplace(chunkStore, filerKey, sizeHint, filerCreator, filerOpener,
                                    rewriteGrower,
                                    (filerMonkey, filerFiler, stackBuffer5, filerLock) -> result.get(), stackBuffer4);
                            }, stackBuffer3);
                    }, stackBuffer2), stackBuffer1);
        }, stackBuffer);
    }

    public <R> void multiWriteNewReplace(byte[] mapName,
        byte[][] filerKeys,
        IndexAlignedHintAndTransactionSupplier<H, M, R> supplier,
        R[] results,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                chunkStore.newChunk(null, skyHookIndexCreator, stackBuffer);
            }
        }
        chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            monkey.readWriteAutoGrow(chunkStore, chunkPower, 2, skyhookCog.creators[chunkPower], skyhookCog.opener,
                skyhookCog.grower,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> skyHookMonkey.readWriteAutoGrow(chunkStore,
                    mapName, null, namedIndexCreator, namedIndexOpener, null,
                    (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                        byte[][][] partitionedFilerKeys = new byte[64][][];
                        int[] numKeys = new int[64];
                        for (int i = 0; i < filerKeys.length; i++) {
                            byte[] filerKey = filerKeys[i];
                            if (filerKey != null) {
                                int chunkPower1 = FilerIO.chunkPower(filerKey.length, 0);
                                if (partitionedFilerKeys[chunkPower1] == null) {
                                    partitionedFilerKeys[chunkPower1] = new byte[filerKeys.length][];
                                }
                                partitionedFilerKeys[chunkPower1][i] = filerKey;
                                numKeys[chunkPower1]++;
                            }
                        }

                        for (int chunkPower1 = 0; chunkPower1 < partitionedFilerKeys.length; chunkPower1++) {
                            if (partitionedFilerKeys[chunkPower1] != null) {
                                int _chunkPower1 = chunkPower1;
                                namedIndexMonkey.readWriteAutoGrow(chunkStore,
                                    chunkPower1,
                                    numKeys[chunkPower1],
                                    namedPowerCreator[chunkPower1],
                                    namedPowerOpener,
                                    namedPowerGrower,
                                    (namedPowerMonkey, namedPowerFiler, stackBuffer4, namedPowerLock) -> {
                                        // TODO consider using the provided filer in appropriate cases.
                                        byte[][] powerFilerKeys = partitionedFilerKeys[_chunkPower1];
                                        for (int i = 0; i < powerFilerKeys.length; i++) {
                                            byte[] filerKey = powerFilerKeys[i];
                                            if (filerKey != null) {
                                                writeMultiFilerKey(namedPowerMonkey, i, filerKey, supplier, results, stackBuffer4);
                                            }
                                        }
                                        return null;
                                    }, stackBuffer3);
                            }
                        }
                        return null;
                    }, stackBuffer2), stackBuffer1);
            return null;
        }, stackBuffer);
    }

    private <R> void writeMultiFilerKey(N namedPowerMonkey,
        int index,
        byte[] filerKey,
        IndexAlignedHintAndTransactionSupplier<H, M, R> keyHintSupplier,
        R[] results,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        HintAndTransaction<H, M, R> hintAndTransaction = namedPowerMonkey.read(
            chunkStore,
            filerKey,
            filerOpener,
            (filerMonkey, filerFiler, stackBuffer5, filerLock) ->
                keyHintSupplier.supply(filerMonkey, filerFiler, stackBuffer5, filerLock, index),
            stackBuffer);

        if (hintAndTransaction != null) {
            H sizeHint = hintAndTransaction.hint;
            ChunkTransaction<M, R> chunkTransaction = hintAndTransaction.filerTransaction;
            final AtomicReference<R> result = new AtomicReference<>();
            GrowFiler<H, M, ChunkFiler> rewriteGrower = rewriteGrowerProvider.create(sizeHint, chunkTransaction, result);
            results[index] = namedPowerMonkey.writeNewReplace(chunkStore, filerKey, sizeHint, filerCreator, filerOpener,
                rewriteGrower,
                (filerMonkey, filerFiler, stackBuffer5, filerLock) -> result.get(), stackBuffer);
        }
    }

    public <R> R read(final byte[] mapName,
        final byte[] filerKey,
        final ChunkTransaction<M, R> filerTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                return filerTransaction.commit(null, null, stackBuffer, null);
            }
        }
        return chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {
            if (monkey == null || filer == null) {
                return filerTransaction.commit(null, null, stackBuffer1, null);
            }

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.read(chunkStore, chunkPower, skyhookCog.opener,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> {
                    if (skyHookMonkey == null || skyHookFiler == null) {
                        return filerTransaction.commit(null, null, stackBuffer2, null);
                    }

                    return skyHookMonkey.read(chunkStore, mapName, namedIndexOpener,
                        (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                            if (namedIndexMonkey == null || namedIndexFiler == null) {
                                return filerTransaction.commit(null, null, stackBuffer3, null);
                            }

                            int chunkPower1 = FilerIO.chunkPower(filerKey.length, 0);
                            return namedIndexMonkey.read(chunkStore, chunkPower1, namedPowerOpener,
                                (namedPowerMonkey, namedPowerFiler, stackBuffer4, namedPowerLock) -> {
                                    if (namedPowerMonkey == null || namedPowerFiler == null) {
                                        return filerTransaction.commit(null, null, stackBuffer4, null);
                                    }
                                    // TODO consider using the provided filer in appropriate cases.
                                    return namedPowerMonkey.read(chunkStore, filerKey, filerOpener, filerTransaction, stackBuffer4);
                                }, stackBuffer3);
                        }, stackBuffer2);
                }, stackBuffer1);
        }, stackBuffer);
    }

    public <R> void readEach(final byte[] mapName, final byte[][] filerKeys, final IndexAlignedChunkTransaction<M, R> filerTransaction, R[] results,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                return;
            }
        }

        final byte[][][] powerFilerKeys = new byte[64][][];
        for (int i = 0; i < filerKeys.length; i++) {
            byte[] filerKey = filerKeys[i];
            if (filerKey != null) {
                int chunkPower = FilerIO.chunkPower(filerKey.length, 0);
                if (powerFilerKeys[chunkPower] == null) {
                    powerFilerKeys[chunkPower] = new byte[filerKeys.length][];
                }
                powerFilerKeys[chunkPower][i] = filerKey;
            }
        }

        chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {
            if (monkey == null || filer == null) {
                return null;
            }

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.read(chunkStore, chunkPower, skyhookCog.opener,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> {
                    if (skyHookMonkey == null || skyHookFiler == null) {
                        return null;
                    }

                    return skyHookMonkey.read(chunkStore, mapName, namedIndexOpener,
                        (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                            if (namedIndexMonkey == null || namedIndexFiler == null) {
                                return null;
                            }

                            for (int chunkPower1 = 0; chunkPower1 < powerFilerKeys.length; chunkPower1++) {
                                final byte[][] keysForMonkey = powerFilerKeys[chunkPower1];
                                if (keysForMonkey != null) {
                                    namedIndexMonkey.read(chunkStore, chunkPower1, namedPowerOpener,
                                        (N namedPowerMonkey, ChunkFiler namedPowerFiler, StackBuffer stackBuffer4, Object namedPowerLock) -> {
                                            if (namedPowerMonkey == null || namedPowerFiler == null) {
                                                return null;
                                            }
                                            // TODO consider using the provided filer in appropriate cases.
                                            for (int i = 0; i < keysForMonkey.length; i++) {
                                                byte[] filerKey = keysForMonkey[i];
                                                if (filerKey != null) {
                                                    int index = i;
                                                    R got = namedPowerMonkey.read(chunkStore,
                                                        filerKey,
                                                        filerOpener,
                                                        (monkey1, filer1, stackBuffer5, lock1)
                                                            -> filerTransaction.commit(monkey1, filer1, stackBuffer5, lock1, index),
                                                        stackBuffer4);
                                                    if (got != null) {
                                                        results[i] = got;
                                                    }
                                                }
                                            }
                                            return null;
                                        }, stackBuffer3);
                                }
                            }
                            return null;
                        }, stackBuffer2);
                }, stackBuffer1);
        }, stackBuffer);
    }

    public Boolean stream(final byte[] mapName,
        final List<KeyRange> ranges,
        final TxStream<byte[], M, ChunkFiler> stream,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                return true;
            }
        }
        return chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {
            if (monkey == null || filer == null) {
                return true;
            }

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.read(chunkStore, chunkPower, skyhookCog.opener,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> {
                    if (skyHookMonkey == null || skyHookFiler == null) {
                        return true;
                    }

                    return skyHookMonkey.read(chunkStore, mapName, namedIndexOpener,
                        (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                            if (namedIndexMonkey == null || namedIndexFiler == null) {
                                return true;
                            }
                            return namedIndexMonkey.stream(null, namedIndexKey -> {
                                Boolean namedIndexResult = namedIndexMonkey.read(chunkStore, namedIndexKey, namedPowerOpener,
                                    (namedPowerMonkey, namedPowerFiler, stackBuffer4, namedPowerLock) -> {
                                        if (namedPowerMonkey == null || namedPowerFiler == null) {
                                            return true;
                                        }
                                        return streamFilerKey(namedPowerMonkey, ranges, stream, stackBuffer4);
                                    }, stackBuffer3);
                                return namedIndexResult;
                            }, stackBuffer3);
                        }, stackBuffer2);
                }, stackBuffer1);
        }, stackBuffer);
    }

    private Boolean streamFilerKey(N namedPowerMonkey,
        List<KeyRange> ranges,
        TxStream<byte[], M, ChunkFiler> stream,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        return namedPowerMonkey.stream(ranges, (byte[] filerKey) -> {
            Boolean filerResult = namedPowerMonkey.<H, M, Boolean>read(chunkStore, filerKey, filerOpener,
                (M filerMonkey, ChunkFiler filerFiler, StackBuffer stackBuffer5, Object filerLock)
                    -> stream.stream(filerKey, filerMonkey, filerFiler, filerLock),
                stackBuffer);
            return filerResult;
        }, stackBuffer);
    }

    public Boolean streamKeys(final byte[] mapName,
        final List<KeyRange> ranges,
        final TxStreamKeys<byte[]> stream,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                return true;
            }
        }
        return chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {
            if (monkey == null || filer == null) {
                return true;
            }

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.read(chunkStore, chunkPower, skyhookCog.opener,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> {
                    if (skyHookMonkey == null || skyHookFiler == null) {
                        return true;
                    }

                    return skyHookMonkey.read(chunkStore, mapName, namedIndexOpener,
                        (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                            if (namedIndexMonkey == null || namedIndexFiler == null) {
                                return true;
                            }
                            return namedIndexMonkey.stream(null, key -> {
                                Boolean namedIndexResult = namedIndexMonkey.read(chunkStore, key, namedPowerOpener,
                                    (N namedPowerMonkey, ChunkFiler namedPowerFiler, StackBuffer stackBuffer4, Object namedPowerLock) -> {
                                        if (namedPowerMonkey == null || namedPowerFiler == null) {
                                            return true;
                                        }
                                        Boolean filerResult = namedPowerMonkey.stream(ranges, stream::stream, stackBuffer4);
                                        return filerResult;
                                    }, stackBuffer3);
                                return namedIndexResult;
                            }, stackBuffer3);
                        }, stackBuffer2);
                }, stackBuffer1);
        }, stackBuffer);

    }

    public long size(byte[] mapName, StackBuffer stackBuffer) throws IOException, InterruptedException {
        synchronized (chunkStore) {
            if (!chunkStore.isValid(constantFP, stackBuffer)) {
                return 0;
            }
        }
        long[] count = new long[1];
        chunkStore.execute(constantFP, skyHookIndexOpener, (monkey, filer, stackBuffer1, lock) -> {
            if (monkey == null || filer == null) {
                return null;
            }

            int chunkPower = FilerIO.chunkPower(mapName.length, 0);
            return monkey.read(chunkStore, chunkPower, skyhookCog.opener,
                (skyHookMonkey, skyHookFiler, stackBuffer2, skyHookLock) -> {
                    if (skyHookMonkey == null || skyHookFiler == null) {
                        return null;
                    }

                    return skyHookMonkey.read(chunkStore, mapName, namedIndexOpener,
                        (namedIndexMonkey, namedIndexFiler, stackBuffer3, namedIndexLock) -> {
                            if (namedIndexMonkey == null || namedIndexFiler == null) {
                                return null;
                            }
                            namedIndexMonkey.stream(null, key -> {
                                namedIndexMonkey.read(chunkStore, key, namedPowerOpener,
                                    (N namedPowerMonkey, ChunkFiler namedPowerFiler, StackBuffer stackBuffer4, Object namedPowerLock) -> {
                                        if (namedPowerMonkey == null || namedPowerFiler == null) {
                                            return null;
                                        }
                                        count[0] += namedPowerMonkey.size(stackBuffer4);
                                        return null;
                                    }, stackBuffer3);
                                return true;
                            }, stackBuffer3);
                            return null;
                        }, stackBuffer2);
                }, stackBuffer1);
        }, stackBuffer);

        return count[0];
    }
}
