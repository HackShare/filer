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

import com.jivesoftware.os.filer.io.AutoGrowingByteBufferBackedFiler;
import com.jivesoftware.os.filer.io.Copyable;
import com.jivesoftware.os.filer.io.CreateFiler;
import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.io.api.ChunkTransaction;
import com.jivesoftware.os.filer.io.api.CorruptionException;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.filer.io.api.StackBuffer.Chunky;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jonathan.colt
 */
public class ChunkStore implements Copyable<ChunkStore> {

    private static final int maxChunkPower = 32;
    private static ChunkMetrics.ChunkMetric[] allocates = new ChunkMetrics.ChunkMetric[maxChunkPower];
    private static ChunkMetrics.ChunkMetric[] reuses = new ChunkMetrics.ChunkMetric[maxChunkPower];
    private static ChunkMetrics.ChunkMetric[] removes = new ChunkMetrics.ChunkMetric[maxChunkPower];
    private static ChunkMetrics.ChunkMetric[] executeHits = new ChunkMetrics.ChunkMetric[maxChunkPower];
    private static ChunkMetrics.ChunkMetric[] executeMisses = new ChunkMetrics.ChunkMetric[maxChunkPower];

    static {
        for (int i = 0; i < maxChunkPower; i++) {
            String size = (i == 0) ? "total" : "2_pow_" + (i > 9 ? i : "0" + i) + "_" + FilerIO.chunkLength(i) + "_bytes";
            allocates[i] = ChunkMetrics.get("ChunkStore", size, "allocate");
            reuses[i] = ChunkMetrics.get("ChunkStore", size, "reuse");
            removes[i] = ChunkMetrics.get("ChunkStore", size, "remove");
            executeHits[i] = ChunkMetrics.get("ChunkStore", size, "executeHit");
            executeMisses[i] = ChunkMetrics.get("ChunkStore", size, "executeMiss");
        }
    }

    private static final long cMagicNumber = Long.MAX_VALUE;
    private static final byte[] zerosMax = new byte[(int) Math.pow(2, 16)]; // 65536 max used until min needed

    private StripedFiler filer;
    private byte[] zerosMin;
    private int minPower;
    private long lengthOfFile;
    private long referenceNumber = 0;

    public ChunkStore(StripedFiler filer) throws Exception {
        this.filer = filer;
    }

    public long getSkyHookFp() {
        return 8 + 8 + (8 * (64 - minPower));
    }

    /*
     * file header format
     * lengthOfFile
     * referenceNumber
     * free 2^8
     * free 2^9
     * thru
     * free 2^64
     */
    public void setup(long _referenceNumber) {
        referenceNumber = _referenceNumber;
        minPower = referenceNumber < 2 ? 8 : 0;
        zerosMin = new byte[(int) Math.pow(2, minPower)];
        lengthOfFile = 8 + 8 + (8 * (64 - minPower));
    }

    /**
     * Approximate length (Doesn't lock filer)
     *
     * @return
     * @throws IOException
     */
    public long sizeInBytes() throws IOException {
        return filer.length();
    }

    public void createAndOpen(StripedFiler filer, StackBuffer stackBuffer) throws Exception {
        this.filer = filer;
        this.filer.rootTx(-1L, (fp, chunkCache, txFiler) -> {
            txFiler.seek(0);
            FilerIO.writeLong(txFiler, lengthOfFile, "lengthOfFile", stackBuffer);
            FilerIO.writeLong(txFiler, referenceNumber, "referenceNumber", stackBuffer);
            for (int i = minPower; i < 65; i++) {
                FilerIO.writeLong(txFiler, -1, "free", stackBuffer);
            }
            txFiler.flush();
            return null;
        });

    }

    public void open(StackBuffer stackBuffer) throws IOException, InterruptedException {
        this.filer.rootTx(-1L, (fp, chunkCache, filer) -> {
            filer.seek(0);
            lengthOfFile = FilerIO.readLong(filer, "lengthOfFile", stackBuffer);
            referenceNumber = FilerIO.readLong(filer, "referenceNumber", stackBuffer);
            minPower = referenceNumber < 2 ? 8 : 0;
            zerosMin = new byte[(int) Math.pow(2, minPower)];
            filer.seek(lengthOfFile);
            return null;
        });
    }

    public void delete() throws IOException {

    }

    @Override
    public void copyTo(final ChunkStore to, StackBuffer stackBuffer) throws IOException, InterruptedException {
        this.filer.rootTx(-1L, (fp, chunkCache, fromFiler) -> {
            to.filer.rootTx(-1L,
                (fp1, chunkCache1, toFiler) -> {
                    fromFiler.seek(0);
                    toFiler.seek(0);
                    //TODO if these filers are both byte buffer backed then it's much faster to do an NIO ByteBuffer.put()
                    FilerIO.copy(fromFiler, toFiler, lengthOfFile, -1);
                    to.open(stackBuffer);
                    return null;
                });
            return null;
        });
    }

    public void rollCache() throws IOException {
        //chunkCache.roll();
    }

    public long getReferenceNumber() {
        return referenceNumber;
    }

    /**
     * @param <M>
     * @param <H>
     * @param hint
     * @param createFiler Nullable
     * @return
     * @throws IOException
     */
    public <M, H> long newChunk(final H hint,
        final CreateFiler<H, M, ChunkFiler> createFiler,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        long _capacity = createFiler.sizeInBytes(hint);
        final int chunkPower = FilerIO.chunkPower(_capacity, minPower);
        final long chunkLength = FilerIO.chunkLength(chunkPower)
            + 8 // add magicNumber
            + 8 // add chunkPower
            + 8 // add next free chunk of equal size
            + 8; // add bytesLength
        final long chunkPosition = freeSeek(chunkPower);
        final AtomicBoolean reused = new AtomicBoolean(false);
        final AtomicLong chunkFP = new AtomicLong(-1);

        this.filer.rootTx(-1L, (fp, chunkCache, filer) -> {
            long reuseFp = reuseChunk(filer, chunkPosition, stackBuffer);

            if (reuseFp == -1) {
                long newChunkFP = lengthOfFile;
                filer.seek(newChunkFP + chunkLength - 1); // last byte in chunk
                filer.write(0); // cause file backed ChunkStore to grow file on disk. Use setLength()?
                filer.seek(newChunkFP);
                FilerIO.writeLong(filer, cMagicNumber, "magicNumber", stackBuffer);
                FilerIO.writeLong(filer, chunkPower, "chunkPower", stackBuffer);
                FilerIO.writeLong(filer, -1, "chunkNexFreeChunkFP", stackBuffer);
                FilerIO.writeLong(filer, chunkLength, "chunkLength", stackBuffer);
                lengthOfFile += chunkLength;
                filer.seek(lengthOfFile); //  force allocation of space
                filer.seek(0);
                FilerIO.writeLong(filer, lengthOfFile, "lengthOfFile", stackBuffer);
                filer.flush();
                reuseFp = newChunkFP;
                reused.set(true);
            }
            chunkFP.set(reuseFp);
            return null;
        });

        if (reused.get()) {
            reuses[0].inc(1);
            reuses[chunkPower].inc(1);
        } else {
            allocates[0].inc(1);
            allocates[chunkPower].inc(1);
        }

        filer.tx(chunkFP.get(), (fp, chunkCache, filer) -> {
            filer.seek(fp);
            long magicNumber = FilerIO.readLong(filer, "magicNumber", stackBuffer);
            if (magicNumber != cMagicNumber) {
                throw new CorruptionException("Invalid chunkFP " + fp);
            }
            int chunkPower1 = (int) FilerIO.readLong(filer, "chunkPower", stackBuffer);
            FilerIO.readLong(filer, "chunkNexFreeChunkFP", stackBuffer);
            FilerIO.readLong(filer, "chunkLength", stackBuffer);
            long startOfFP = filer.getFilePointer();
            long endOfFP = startOfFP + FilerIO.chunkLength(chunkPower1);
            ChunkFiler chunkFiler = new ChunkFiler(ChunkStore.this, filer.duplicate(stackBuffer.duplicateBuffer, startOfFP, endOfFP), fp, startOfFP, endOfFP);
            chunkFiler.seek(0);
            M monkey = createFiler.create(hint, chunkFiler, stackBuffer);
            chunkCache.set(fp, new Chunk<>(monkey, fp, chunkPower, startOfFP, endOfFP), 2, stackBuffer);
            return null;
        });
        return chunkFP.get();
    }

    /**
     * Synchronize externally on filer.lock()
     */
    private long reuseChunk(Filer filer, long position, StackBuffer stackBuffer) throws IOException {
        filer.seek(position);
        long reuseFP = FilerIO.readLong(filer, "free", stackBuffer);
        if (reuseFP == -1) {
            return reuseFP;
        }
        long nextFree = readNextFree(filer, reuseFP, stackBuffer);
        filer.seek(position);
        FilerIO.writeLong(filer, nextFree, "free", stackBuffer);
        return reuseFP;
    }

    /**
     * Synchronize externally on filer.lock()
     */
    private long readNextFree(Filer filer, long _chunkFP, StackBuffer stackBuffer) throws IOException {
        filer.seek(_chunkFP);
        FilerIO.readLong(filer, "magicNumber", stackBuffer);
        FilerIO.readLong(filer, "chunkPower", stackBuffer);
        return FilerIO.readLong(filer, "chunkNexFreeChunkFP", stackBuffer);
    }

    /**
     * Synchronize externally on filer.lock()
     */
    private void writeNextFree(Filer filer, long _chunkFP, long _nextFreeFP, StackBuffer stackBuffer) throws IOException {
        filer.seek(_chunkFP);
        FilerIO.readLong(filer, "magicNumber", stackBuffer);
        FilerIO.readLong(filer, "chunkPower", stackBuffer);
        FilerIO.writeLong(filer, _nextFreeFP, "chunkNexFreeChunkFP", stackBuffer);
    }

    /**
     * @param <M>
     * @param <R>
     * @param chunkFP
     * @param openFiler
     * @param chunkTransaction
     * @return
     * @throws IOException
     */
    public <M, R> R execute(final long chunkFP,
        final OpenFiler<M, ChunkFiler> openFiler,
        final ChunkTransaction<M, R> chunkTransaction,
        StackBuffer stackBuffer)
        throws IOException, InterruptedException {

        Chunky<M> chunky = filer.tx(chunkFP, (fp, chunkCache, filer) -> {
            Chunk<M> chunk = chunkCache.acquireIfPresent(chunkFP, stackBuffer);
            if (chunk == null) {
                filer.seek(chunkFP);
                long magicNumber = FilerIO.readLong(filer, "magicNumber", stackBuffer);
                if (magicNumber != cMagicNumber) {
                    throw new CorruptionException("Invalid chunkFP " + chunkFP);
                }
                int chunkPower = (int) FilerIO.readLong(filer, "chunkPower", stackBuffer);
                FilerIO.readLong(filer, "chunkNexFreeChunkFP", stackBuffer);
                FilerIO.readLong(filer, "chunkLength", stackBuffer);
                long startOfFP = filer.getFilePointer();

                long endOfFP = startOfFP + FilerIO.chunkLength(chunkPower);
                ChunkFiler chunkFiler = stackBuffer.chunkFiler(ChunkStore.this, filer.duplicate(stackBuffer.duplicateBuffer, startOfFP, endOfFP), chunkFP,
                    startOfFP, endOfFP);
                chunkFiler.seek(0);

                M monkey = openFiler.open(chunkFiler, stackBuffer);
                chunk = new Chunk<>(monkey, chunkFP, chunkPower, startOfFP, endOfFP);
                chunkCache.promoteAndAcquire(chunkFP, chunk, 2, stackBuffer);

                executeMisses[0].inc(1);
                executeMisses[chunkPower].inc(1);
            } else {
                executeHits[0].inc(1);
                executeHits[chunk.chunkPower].inc(1);
            }

            //ChunkFiler chunkFiler = new ChunkFiler(ChunkStore.this, filer.duplicate(chunk.startOfFP, chunk.endOfFP), chunkFP, chunk.startOfFP,
            //    chunk.endOfFP);
            AutoGrowingByteBufferBackedFiler duplicate = filer.duplicate(stackBuffer.duplicateBuffer, chunk.startOfFP, chunk.endOfFP);
            ChunkFiler chunkFiler = stackBuffer.chunkFiler(ChunkStore.this, duplicate, chunkFP, chunk.startOfFP,
                chunk.endOfFP);
            chunkFiler.seek(0);
            return stackBuffer.chunky(duplicate, chunkFiler, chunk);
        });

        try {
            return chunkTransaction.commit(chunky.monkey.monkey, chunky.filer, stackBuffer, chunky.monkey);
        } finally {

            filer.tx(chunkFP, (fp, chunkCache, filer1) -> {
                chunkCache.release(chunkFP, stackBuffer);
                return null;
            });

            ChunkFiler chunkyFiler = chunky.filer;
            AutoGrowingByteBufferBackedFiler chunkyDuplicate = chunky.duplicate;
            stackBuffer.recycle(chunkyFiler);
            stackBuffer.duplicateBuffer.recycle(chunkyDuplicate);
            stackBuffer.recycle(chunky);
        }
    }

    public void remove(long chunkFP, StackBuffer stackBuffer) throws IOException, InterruptedException {

        final Integer chunkPower = filer.tx(chunkFP, (fp, chunkCache, filer) -> {
            chunkCache.remove(fp, stackBuffer);

            filer.seek(fp);
            long magicNumber = FilerIO.readLong(filer, "magicNumber", stackBuffer);
            if (magicNumber != cMagicNumber) {
                throw new CorruptionException("Invalid chunkFP " + fp);
            }
            int chunkPower1 = (int) FilerIO.readLong(filer, "chunkPower", stackBuffer);
            FilerIO.readLong(filer, "chunkNexFreeChunkFP", stackBuffer);
            FilerIO.writeLong(filer, -1, "chunkLength", stackBuffer);
            long chunkLength = FilerIO.chunkLength(chunkPower1); // bytes
            // fill with zeros
            while (chunkLength >= zerosMax.length) {
                filer.write(zerosMax);
                chunkLength -= zerosMax.length;
            }
            while (chunkLength >= zerosMin.length) {
                filer.write(zerosMin);
                chunkLength -= zerosMin.length;
            }
            filer.flush();
            return chunkPower1;
        });

        filer.rootTx(chunkFP, (fp, chunkCache, filer) -> {

            // save as free chunk
            long position = freeSeek(chunkPower);
            filer.seek(position);
            long freeFP = FilerIO.readLong(filer, "free", stackBuffer);
            if (freeFP == -1) {
                filer.seek(position);
                FilerIO.writeLong(filer, fp, "free", stackBuffer);
            } else if (fp != freeFP) {
                filer.seek(position);
                FilerIO.writeLong(filer, fp, "free", stackBuffer);
            } else {
                System.err.println("WARNING: Some one is removing the same chunk more than once. chunkFP:" + fp);
                new RuntimeException().printStackTrace();
            }
            writeNextFree(filer, fp, freeFP, stackBuffer);
            filer.flush();
            return null;
        });

        removes[0].inc(1);
        removes[chunkPower].inc(1);
    }

    private long freeSeek(long _chunkPower) {
        return 8 + 8 + ((_chunkPower - minPower) * 8);
    }

    public boolean isValid(final long chunkFP, StackBuffer stackBuffer) throws IOException, InterruptedException {
        return filer.tx(chunkFP, (fp, chunkCache, filer) -> {
            if (chunkCache.contains(fp, stackBuffer)) {
                return true;
            }
            filer.seek(fp);
            long magicNumber = FilerIO.readLong(filer, "magicNumber", stackBuffer);
            return magicNumber == cMagicNumber;
        });
    }

}
