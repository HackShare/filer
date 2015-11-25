/*
 * Copyright 2015 Jive Software.
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
package com.jivesoftware.os.filer.io.chunk;

import com.jivesoftware.os.filer.io.Copyable;
import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.filer.io.api.ChunkTransaction;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author jonathan.colt
 */
public class TxChunkStore implements Copyable<ChunkStore> {

    private final ChunkStore overlay;

    private final ChunkStore master;
    final ConcurrentHashMap<Integer, BlockingQueue<Long>> reusableMasterFP = new ConcurrentHashMap<>();

    private final AtomicBoolean txing = new AtomicBoolean(false);
    private final FPLUT fplut;

    public TxChunkStore(ChunkStore overlay, ChunkStore master, FPLUT fplut) {
        this.overlay = overlay;
        this.master = master;
        this.fplut = fplut;
    }

    static public class FPLUT { // FP look up table

        private final StripingLocksProvider<Long> masterFPLocksProvider;
        final Map<OverlayFP, Long> overlayToMaster = new ConcurrentHashMap<>();
        final Map<Long, OverlayFP> masterToOverlay = new ConcurrentHashMap<>();

        public FPLUT(StripingLocksProvider<Long> masterFPLocksProvider) {
            this.masterFPLocksProvider = masterFPLocksProvider;
        }

        <R> R get(Long masterFP, FPTx<R> fpTx) throws IOException, InterruptedException {
            synchronized (masterFPLocksProvider.lock(masterFP, 0)) {
                return fpTx.tx(masterToOverlay.get(masterFP), masterFP);
            }
        }

        void set(OverlayFP overlayFP, Long masterFP) {
            synchronized (masterFPLocksProvider.lock(masterFP, 0)) {
                overlayToMaster.put(overlayFP, masterFP);
                masterToOverlay.put(masterFP, overlayFP);
            }
        }

        <R> R remove(long masterFP, FPTx<R> fpTx) throws IOException, InterruptedException {
            synchronized (masterFPLocksProvider.lock(masterFP, 0)) {
                OverlayFP overlayFP = masterToOverlay.get(masterFP);
                R r = fpTx.tx(overlayFP, masterFP);
                overlayToMaster.remove(overlayFP);
                masterToOverlay.remove(masterFP);
                return r;
            }
        }

        boolean contains(long masterFP, FPTx<Boolean> fpTx) throws IOException, InterruptedException {
            synchronized (masterFPLocksProvider.lock(masterFP, 0)) {
                return fpTx.tx(masterToOverlay.get(masterFP), masterFP);
            }
        }

        void removeAll(FPTx<Void> fpTx) throws IOException, InterruptedException {
            List<OverlayFP> overlayFPs = new ArrayList<>(overlayToMaster.keySet());
            Collections.sort(overlayFPs);
            for (OverlayFP overlayFP : overlayFPs) {
                synchronized (masterFPLocksProvider.lock(overlayFP.masterFP, 0)) {
                    remove(overlayFP.masterFP, fpTx);
                }
            }
        }
    }

    static interface FPTx<R> {

        R tx(OverlayFP overlayFP, Long masterFP) throws IOException, InterruptedException;
    }

    @Override
    public void copyTo(ChunkStore to, StackBuffer stackBuffer) throws IOException, InterruptedException {
        if (txing.compareAndSet(false, true)) {
            commit(stackBuffer);
            master.copyTo(to, stackBuffer);
            txing.set(false);
        }
    }

    public void begin(Object checkPointId) {
        if (txing.compareAndSet(false, true)) {
        } else {
            throw new IllegalStateException("Called begin on an open transaction.");
        }
    }

    public void commit(StackBuffer stackBuffer) throws IOException, InterruptedException {

        fplut.removeAll((overlayFP, masterFP) -> {
            //master.slabTransfer(overlay, overlayFP.overlayFP, masterFP);
            return null;
        });

        for (Integer chunkPower : reusableMasterFP.keySet()) {
            BlockingQueue<Long> free = reusableMasterFP.get(chunkPower);
            for (Long f = free.poll(); f != null; f = free.poll()) {
                master.remove(f, stackBuffer);
            }
        }

        // overlay.reset();
    }

    public <M, H> long newChunk(int level, final H hint, final CreateFiler<H, M, ChunkFiler> createFiler, StackBuffer stackBuffer) throws IOException,
        InterruptedException {

        long capacity = createFiler.sizeInBytes(hint);
        int chunkPower = FilerIO.chunkPower(capacity, ChunkStore.cMinPower);
        Long masterFP = null;
        BlockingQueue<Long> free = reusableMasterFP.get(chunkPower);
        if (free != null) {
            masterFP = free.poll();
        }
        if (masterFP == null) {
            masterFP = master.newChunk(hint, createFiler, stackBuffer);
        }

        OverlayFP overlayFP = new OverlayFP(level, chunkPower, overlay.newChunk(hint, createFiler, stackBuffer), masterFP);
        fplut.set(overlayFP, masterFP);
        return masterFP;
    }

    public <M, R> R readOnly(final int level,
        final long masterFP,
        final OpenFiler<M, ChunkFiler> openFiler,
        final ChunkTransaction<M, R> chunkTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        return execute(level, masterFP, openFiler, chunkTransaction, stackBuffer);
    }

    public <M, R> R readWrite(final int level,
        final long masterFP,
        final OpenFiler<M, ChunkFiler> openFiler,
        final ChunkTransaction<M, R> chunkTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        return execute(level, masterFP, openFiler, chunkTransaction, stackBuffer);
    }

    <M, R> R execute(final int level,
        long masterFP,
        final OpenFiler<M, ChunkFiler> openFiler,
        final ChunkTransaction<M, R> chunkTransaction,
        StackBuffer stackBuffer) throws IOException, InterruptedException {

        return fplut.get(masterFP, (overlayFP, gotMasterFp) -> {

            if (overlayFP == null) {
                overlayFP = master.execute(gotMasterFp, openFiler, (monkey, masterFiler, _stackBuffer, lock) -> {
                    synchronized (lock) {
                        long size = masterFiler.getSize();
                        int chunkPower = FilerIO.chunkPower(size, ChunkStore.cMinPower); // Grrr
                        long fp = overlay.newChunk(size, new CreateFiler<Long, M, ChunkFiler>() {

                            @Override
                            public long sizeInBytes(Long hint) throws IOException {
                                return hint;
                            }

                            @Override
                            public M create(Long hint, ChunkFiler overlayFiler, StackBuffer stackBuffer) throws IOException {
                                FilerIO.copy(masterFiler, overlayFiler, -1);
                                return monkey;
                            }

                        }, _stackBuffer);
                        return new OverlayFP(level, chunkPower, fp, gotMasterFp);
                    }
                }, stackBuffer);
                fplut.set(overlayFP, overlayFP.masterFP);

            }
            return overlay.execute(overlayFP.overlayFP, openFiler, chunkTransaction, stackBuffer);
        });

    }

    public void remove(final long masterFP, StackBuffer stackBuffer) throws IOException, InterruptedException {
        fplut.remove(masterFP, (overlayFP, masterFP1) -> {
            if (overlayFP != null) {
                BlockingQueue<Long> free = reusableMasterFP.get(overlayFP.power);
                if (free == null) {
                    free = new LinkedBlockingQueue<>(Integer.MAX_VALUE);
                    BlockingQueue<Long> had = reusableMasterFP.putIfAbsent(overlayFP.power, free);
                    if (had != null) {
                        free = had;
                    }
                }
                free.add(masterFP1);

            } else {
                master.remove(masterFP1, stackBuffer);
            }
            return null;
        });
    }

    public boolean isValid(long masterFP, StackBuffer stackBuffer) throws IOException, InterruptedException {
        return fplut.contains(masterFP, (overlayFP, gotMasterFp) -> {
            if (overlayFP != null) {
                return true;
            }
            return master.isValid(gotMasterFp, stackBuffer);
        });

    }

    private static class OverlayFP implements Comparable<OverlayFP> {

        public final int level;
        public final int power;
        public final long overlayFP;
        public final long masterFP;

        public OverlayFP(int level, int chunkPower, long chunkFP, long masterFP) {
            this.level = level;
            this.power = chunkPower;
            this.overlayFP = chunkFP;
            this.masterFP = masterFP;
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 37 * hash + (int) (this.overlayFP ^ (this.overlayFP >>> 32));
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final OverlayFP other = (OverlayFP) obj;
            if (this.overlayFP != other.overlayFP) {
                return false;
            }
            return true;
        }

        /**
         * Sort based on level descending and then master fp ascending.
         *
         * @param o
         * @return
         */
        @Override
        public int compareTo(OverlayFP o) {
            int c = -Integer.compare(level, o.level);
            if (c == 0) {
                c = Long.compare(masterFP, o.masterFP);
            }
            return c;
        }

    }

}
