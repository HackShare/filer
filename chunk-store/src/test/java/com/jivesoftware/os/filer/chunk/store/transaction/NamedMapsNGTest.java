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

import com.jivesoftware.os.filer.chunk.store.ChunkStoreInitializer;
import com.jivesoftware.os.filer.io.ByteArrayPartitionFunction;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.FilerLock;
import com.jivesoftware.os.filer.io.HeapByteBufferFactory;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import com.jivesoftware.os.filer.io.chunk.ChunkFiler;
import com.jivesoftware.os.filer.io.chunk.ChunkStore;
import com.jivesoftware.os.filer.io.map.MapStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author jonathan.colt
 */
public class NamedMapsNGTest {

    @Test
    public void testVariableMapNameSizesCommit() throws Exception {
        StackBuffer stackBuffer = new StackBuffer();
        HeapByteBufferFactory byteBufferFactory = new HeapByteBufferFactory();
        File dir = Files.createTempDirectory("testNewChunkStore").toFile();
        ChunkStore chunkStore1 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data1", 8, byteBufferFactory, 500, 5_000, stackBuffer);
        ChunkStore chunkStore2 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data2", 8, byteBufferFactory, 500, 5_000, stackBuffer);

        TxCogs cogs = new TxCogs(256, 64, null, null, null);
        TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> skyhookCog = cogs.getSkyhookCog(0);

        TxPartitionedNamedMap namedMap = new TxPartitionedNamedMap(ByteArrayPartitionFunction.INSTANCE, new TxNamedMap[]{
            new TxNamedMap(skyhookCog, 0, chunkStore1, new MapCreator(2, 4, true, 8, false), MapOpener.INSTANCE, new MapGrower<>(),
            cogs.getSkyHookKeySemaphores()),
            new TxNamedMap(skyhookCog, 0, chunkStore2, new MapCreator(2, 4, true, 8, false), MapOpener.INSTANCE, new MapGrower<>(),
            cogs.getSkyHookKeySemaphores())
        });

        int tries = 128;

        READS_AGAINST_EMPTY:
        {
            final Random rand = new Random(1234);
            final AtomicInteger nulls = new AtomicInteger();
            for (int i = 0; i < tries; i++) {
                byte[] mapName = new byte[1 + rand.nextInt(1024)];
                rand.nextBytes(mapName);
                mapName[0] = (byte) i;
                namedMap.read(mapName, mapName, (monkey, filer, stackBuffer1, lock) -> {
                    Assert.assertNull(monkey);
                    Assert.assertNull(filer);
                    nulls.addAndGet(1);
                    return null;
                }, stackBuffer);
            }
            Assert.assertEquals(nulls.get(), tries);
        }

        WRITES:
        {
            final Random rand = new Random(1234);
            for (int i = 0; i < tries; i++) {
                byte[] mapName = new byte[1 + rand.nextInt(1024)];
                rand.nextBytes(mapName);
                mapName[0] = (byte) i;
                for (int a = 0; a < 10; a++) {
                    final int ai = a;
                    namedMap.readWriteAutoGrow(mapName, mapName, (monkey, filer, stackBuffer1, lock) -> {
                        synchronized (lock) {
                            byte[] key = new byte[1 + rand.nextInt(3)];
                            rand.nextBytes(key);
                            key[0] = (byte) ai;
                            MapStore.INSTANCE.add(filer, monkey, (byte) 1, key, FilerIO.longBytes(rand.nextLong()), stackBuffer1);
                            return null;
                        }
                    }, stackBuffer);
                }
            }
        }

        MISSING_READS:
        {
            final Random rand = new Random(1234);
            final AtomicInteger nulls = new AtomicInteger();
            for (int i = tries; i < tries * 2; i++) {
                byte[] mapName = new byte[1 + rand.nextInt(1024)];
                rand.nextBytes(mapName);
                mapName[0] = (byte) i;
                namedMap.read(mapName, mapName, (monkey, filer, stackBuffer1, lock) -> {
                    Assert.assertNull(monkey);
                    Assert.assertNull(filer);
                    nulls.addAndGet(1);
                    return null;
                }, stackBuffer);
            }
            Assert.assertEquals(nulls.get(), tries);
        }

        REMOVES:
        {
            final Random rand = new Random(1234);
            for (int i = 0; i < tries; i++) {
                byte[] mapName = new byte[1 + rand.nextInt(1024)];
                rand.nextBytes(mapName);
                mapName[0] = (byte) i;
                for (int a = 0; a < 10; a++) {
                    final int ai = a;
                    namedMap.read(mapName, mapName, (monkey, filer, stackBuffer1, lock) -> {
                        synchronized (lock) {
                            byte[] key = new byte[1 + rand.nextInt(3)];
                            rand.nextBytes(key);
                            key[0] = (byte) ai;

                            long expected = rand.nextLong();
                            byte[] got = MapStore.INSTANCE.getPayload(filer, monkey, key, stackBuffer1);
                            Assert.assertNotNull(got);
                            Assert.assertEquals(FilerIO.bytesLong(got), expected);
                            return null;
                        }
                    }, stackBuffer);
                }
            }
        }

    }

    @Test
    public void testVariableNamedMapOfFilers() throws Exception {
        StackBuffer stackBuffer = new StackBuffer();
        HeapByteBufferFactory byteBufferFactory = new HeapByteBufferFactory();
        File dir = Files.createTempDirectory("testVariableNamedMapOfFilers").toFile();
        ChunkStore chunkStore1 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data1", 8, byteBufferFactory, 500, 5_000, stackBuffer);
        ChunkStore chunkStore2 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data2", 8, byteBufferFactory, 500, 5_000, stackBuffer);

        TxCogs cogs = new TxCogs(256, 64, null, null, null);
        TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> skyhookCog = cogs.getSkyhookCog(0);
        TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> powerCog = cogs.getPowerCog(0);

        TxPartitionedNamedMapOfFiler<MapBackedKeyedFPIndex, Long, FilerLock> namedMapOfFilers = new TxPartitionedNamedMapOfFiler<>(
            ByteArrayPartitionFunction.INSTANCE,
            (TxNamedMapOfFiler<MapBackedKeyedFPIndex, Long, FilerLock>[]) new TxNamedMapOfFiler[]{
                new TxNamedMapOfFiler<>(skyhookCog, 0, chunkStore1,
                    powerCog.creators,
                    powerCog.opener,
                    powerCog.grower,
                    TxNamedMapOfFiler.CHUNK_FILER_CREATOR,
                    TxNamedMapOfFiler.CHUNK_FILER_OPENER,
                    cogs.getSkyHookKeySemaphores(),
                    cogs.getNamedKeySemaphores(),
                    TxNamedMapOfFiler.OVERWRITE_GROWER_PROVIDER,
                    TxNamedMapOfFiler.REWRITE_GROWER_PROVIDER),
                new TxNamedMapOfFiler<>(skyhookCog, 0, chunkStore2,
                    powerCog.creators,
                    powerCog.opener,
                    powerCog.grower,
                    TxNamedMapOfFiler.CHUNK_FILER_CREATOR,
                    TxNamedMapOfFiler.CHUNK_FILER_OPENER,
                    cogs.getSkyHookKeySemaphores(),
                    cogs.getNamedKeySemaphores(),
                    TxNamedMapOfFiler.OVERWRITE_GROWER_PROVIDER,
                    TxNamedMapOfFiler.REWRITE_GROWER_PROVIDER)
            });

        int tries = 128;

        WRITES:
        {
            final Random rand = new Random(1234);
            for (int i = 0; i < tries; i++) {
                byte[] mapName = new byte[1 + rand.nextInt(1024)];
                rand.nextBytes(mapName);
                mapName[0] = (byte) i;

                for (int f = 0; f < tries; f++) {
                    byte[] filerName = new byte[1 + rand.nextInt(1024)];
                    rand.nextBytes(filerName);
                    filerName[0] = (byte) f;

                    namedMapOfFilers.readWriteAutoGrow(mapName, mapName, filerName, 8L, (monkey, filer, stackBuffer1, lock) -> {
                        synchronized (lock) {
                            FilerIO.writeLong(filer, rand.nextLong(), "value", stackBuffer1);
                            return null;
                        }
                    }, stackBuffer);
                }

            }
        }

        final Random readRandom = new Random(1234);
        for (int i = 0; i < tries; i++) {
            byte[] mapName = new byte[1 + readRandom.nextInt(1024)];
            readRandom.nextBytes(mapName);
            mapName[0] = (byte) i;
            for (int f = 0; f < tries; f++) {
                byte[] filerName = new byte[1 + readRandom.nextInt(1024)];
                readRandom.nextBytes(filerName);
                filerName[0] = (byte) f;
                namedMapOfFilers.read(mapName, mapName, filerName, (monkey, filer, stackBuffer1, lock) -> {
                    synchronized (lock) {
                        Assert.assertNotNull(filer);
                        long expected = readRandom.nextLong();
                        Assert.assertEquals(FilerIO.readLong(filer, "value", stackBuffer1), expected);
                        return null;
                    }
                }, stackBuffer);
            }
        }
    }

    @Test
    public void testCommit() throws Exception, InterruptedException {
        StackBuffer stackBuffer = new StackBuffer();
        HeapByteBufferFactory byteBufferFactory = new HeapByteBufferFactory();
        File dir = Files.createTempDirectory("testCommit").toFile();
        ChunkStore chunkStore1 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data1", 8, byteBufferFactory, 500, 5_000, stackBuffer);
        ChunkStore chunkStore2 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data2", 8, byteBufferFactory, 500, 5_000, stackBuffer);

        TxCogs cogs = new TxCogs(256, 64, null, null, null);
        TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> skyhookCog = cogs.getSkyhookCog(0);
        TxCog<Integer, MapBackedKeyedFPIndex, ChunkFiler> powerCog = cogs.getPowerCog(0);

        TxPartitionedNamedMapOfFiler<MapBackedKeyedFPIndex, Long, FilerLock> namedMapOfFilers = new TxPartitionedNamedMapOfFiler<>(
            ByteArrayPartitionFunction.INSTANCE,
            (TxNamedMapOfFiler<MapBackedKeyedFPIndex, Long, FilerLock>[]) new TxNamedMapOfFiler[]{
                new TxNamedMapOfFiler<>(skyhookCog, 0, chunkStore1,
                    powerCog.creators,
                    powerCog.opener,
                    powerCog.grower,
                    TxNamedMapOfFiler.CHUNK_FILER_CREATOR,
                    TxNamedMapOfFiler.CHUNK_FILER_OPENER,
                    cogs.getSkyHookKeySemaphores(),
                    cogs.getNamedKeySemaphores(),
                    TxNamedMapOfFiler.OVERWRITE_GROWER_PROVIDER,
                    TxNamedMapOfFiler.REWRITE_GROWER_PROVIDER),
                new TxNamedMapOfFiler<>(skyhookCog, 0, chunkStore2,
                    powerCog.creators,
                    powerCog.opener,
                    powerCog.grower,
                    TxNamedMapOfFiler.CHUNK_FILER_CREATOR,
                    TxNamedMapOfFiler.CHUNK_FILER_OPENER,
                    cogs.getSkyHookKeySemaphores(),
                    cogs.getNamedKeySemaphores(),
                    TxNamedMapOfFiler.OVERWRITE_GROWER_PROVIDER,
                    TxNamedMapOfFiler.REWRITE_GROWER_PROVIDER)
            });

        TxPartitionedNamedMap namedMap = new TxPartitionedNamedMap(ByteArrayPartitionFunction.INSTANCE, new TxNamedMap[]{
            new TxNamedMap(skyhookCog, 0, chunkStore1, new MapCreator(2, 4, true, 8, false), MapOpener.INSTANCE, new MapGrower<>(),
            cogs.getSkyHookKeySemaphores()),
            new TxNamedMap(skyhookCog, 0, chunkStore2, new MapCreator(2, 4, true, 8, false), MapOpener.INSTANCE, new MapGrower<>(),
            cogs.getSkyHookKeySemaphores())
        });

        final int addCount = 16;
        for (int c = 0; c < 10; c++) {

            int accum = 0;
            for (int i = 0; i < addCount; i++) {
                accum += i;
                final int key = i;

                namedMap.readWriteAutoGrow(FilerIO.intBytes(c), "map1".getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                    synchronized (lock) {
                        MapStore.INSTANCE.add(filer, monkey, (byte) 1, String.valueOf(key)
                            .getBytes(), FilerIO.longBytes(key), stackBuffer1);
                        return null;
                    }
                }, stackBuffer);

                namedMapOfFilers.readWriteAutoGrow(FilerIO.intBytes(c), "filer1".getBytes(), (c + "overwrite").getBytes(), 8L,
                    (monkey, filer, stackBuffer1, lock) -> {
                        synchronized (lock) {
                            FilerIO.writeLong(filer, key, "value", stackBuffer1);
                            //System.out.println("Overwrite:" + key + " " + filer.getChunkFP());
                            return null;
                        }
                    }, stackBuffer);

                namedMapOfFilers.writeNewReplace(FilerIO.intBytes(c), "filer2".getBytes(), (c + "rewrite").getBytes(), 8L,
                    (newMonkey, newFiler, stackBuffer1, newLock) -> {

                        synchronized (newLock) {
                            FilerIO.writeLong(newFiler, key, "value", stackBuffer1);
                            //System.out.println("Rewrite:" + (oldValue + key) + " " + newFiler.getChunkFP());
                            return null;
                        }

                    }, stackBuffer);

                //System.out.println("Accum:" + accum);
            }

            final AtomicBoolean failed = new AtomicBoolean();
            for (int i = 0; i < addCount; i++) {
                final int key = i;

                namedMap.read(FilerIO.intBytes(c), "map1".getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                    synchronized (lock) {
                        long i1 = MapStore.INSTANCE.get(filer, monkey, String.valueOf(key)
                            .getBytes(), stackBuffer1);
                        long value = FilerIO.bytesLong(MapStore.INSTANCE.getPayload(filer, monkey, i1, stackBuffer1));
                        //System.out.println("expected:" + key + " got:" + value + " from " + context.filer.getChunkFP());
                        if (value != key) {
                            System.out.println("mapRead FAILED. " + value + " vs " + key);
                            failed.set(true);
                        }
                        return null;
                    }
                }, stackBuffer);

                namedMapOfFilers.read(FilerIO.intBytes(c), "filer1".getBytes(), (c + "overwrite").getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                    synchronized (lock) {
                        long v = FilerIO.readLong(filer, "value", stackBuffer);
                        //System.out.println("OR:" + v);
                        if (v != addCount - 1) {
                            System.out.println("filerReadOverwrite FAILED. " + v + " vs " + (addCount - 1));
                            failed.set(true);
                        }
                        return null;
                    }
                }, stackBuffer);

                namedMapOfFilers.read(FilerIO.intBytes(c), "filer2".getBytes(), (c + "rewrite").getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                    synchronized (lock) {
                        long v = FilerIO.readLong(filer, "value", stackBuffer1);
                        //System.out.println("RR:" + v + " from " + filer.getChunkFP());
                        if (v != addCount - 1) {
                            System.out.println("filerReadRewrite FAILED. " + v + " vs " + (addCount - 1));
                            failed.set(true);
                        }
                        return null;
                    }
                }, stackBuffer);

            }
            Assert.assertFalse(failed.get());

        }

        chunkStore1 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data1", 8, byteBufferFactory, 500, 5_000, stackBuffer);
        chunkStore2 = new ChunkStoreInitializer().openOrCreate(new File[]{dir}, 0, "data2", 8, byteBufferFactory, 500, 5_000, stackBuffer);

        namedMapOfFilers = new TxPartitionedNamedMapOfFiler<>(ByteArrayPartitionFunction.INSTANCE,
            (TxNamedMapOfFiler<MapBackedKeyedFPIndex, Long, FilerLock>[]) new TxNamedMapOfFiler[]{
                new TxNamedMapOfFiler<>(skyhookCog, 0, chunkStore1,
                    powerCog.creators,
                    powerCog.opener,
                    powerCog.grower,
                    TxNamedMapOfFiler.CHUNK_FILER_CREATOR,
                    TxNamedMapOfFiler.CHUNK_FILER_OPENER,
                    cogs.getSkyHookKeySemaphores(),
                    cogs.getNamedKeySemaphores(),
                    TxNamedMapOfFiler.OVERWRITE_GROWER_PROVIDER,
                    TxNamedMapOfFiler.REWRITE_GROWER_PROVIDER),
                new TxNamedMapOfFiler<>(skyhookCog, 0, chunkStore2,
                    powerCog.creators,
                    powerCog.opener,
                    powerCog.grower,
                    TxNamedMapOfFiler.CHUNK_FILER_CREATOR,
                    TxNamedMapOfFiler.CHUNK_FILER_OPENER,
                    cogs.getSkyHookKeySemaphores(),
                    cogs.getNamedKeySemaphores(),
                    TxNamedMapOfFiler.OVERWRITE_GROWER_PROVIDER,
                    TxNamedMapOfFiler.REWRITE_GROWER_PROVIDER)
            });

        namedMap = new TxPartitionedNamedMap(ByteArrayPartitionFunction.INSTANCE, new TxNamedMap[]{
            new TxNamedMap(skyhookCog, 0, chunkStore1, new MapCreator(2, 4, true, 8, false), MapOpener.INSTANCE, new MapGrower<>(),
            cogs.getSkyHookKeySemaphores()),
            new TxNamedMap(skyhookCog, 0, chunkStore2, new MapCreator(2, 4, true, 8, false), MapOpener.INSTANCE, new MapGrower<>(),
            cogs.getSkyHookKeySemaphores())
        });

        for (int c = 0; c < 10; c++) {

            doItAgain(addCount, namedMap, c, namedMapOfFilers, stackBuffer);

        }

        namedMapOfFilers.stream("filer2".getBytes(), null, (key, monkey, filer, lock) -> {
            //System.out.println(new IBA(key) + " " + filer);
            return true;
        }, stackBuffer);

        namedMapOfFilers.streamKeys("filer2".getBytes(), null, key -> {
            //System.out.println(new IBA(key));
            return true;
        }, stackBuffer);

        namedMap.stream("map1".getBytes(), (key, monkey, filer, lock) -> {
            MapStore.INSTANCE.streamKeys(filer, monkey, lock, key1 -> {
                //System.out.println(new IBA(key));
                return true;
            }, stackBuffer);
            return true;
        }, stackBuffer);
    }

    private void doItAgain(final int addCount, TxPartitionedNamedMap namedMap, int c,
        TxPartitionedNamedMapOfFiler<MapBackedKeyedFPIndex, Long, FilerLock> namedMapOfFilers,
        StackBuffer stackBuffer) throws IOException, InterruptedException {
        int accum = 0;
        for (int i = 0; i < addCount; i++) {
            accum += i;
        }
        final AtomicBoolean failed = new AtomicBoolean();
        for (int i = 0; i < addCount; i++) {
            final int key = i;
            namedMap.read(FilerIO.intBytes(c), "map1".getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                synchronized (lock) {
                    long i1 = MapStore.INSTANCE.get(filer, monkey, String.valueOf(key)
                        .getBytes(), stackBuffer1);
                    long value = FilerIO.bytesLong(MapStore.INSTANCE.getPayload(filer, monkey, i1, stackBuffer1));
                    //System.out.println("expected:" + key + " got:" + value + " from " + context.filer.getChunkFP());
                    if (value != key) {
                        //System.out.println("on re-open mapRead FAILED. " + value + " vs " + key);
                        failed.set(true);
                    }
                    return null;
                }
            }, stackBuffer);

            namedMapOfFilers.read(FilerIO.intBytes(c), "filer1".getBytes(), (c + "overwrite").getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                synchronized (lock) {
                    long v = FilerIO.readLong(filer, "value", stackBuffer1);
                    //System.out.println("OR:" + v);
                    if (v != addCount - 1) {
                        //System.out.println("filerReadOverwrite FAILED. " + v + " vs " + (addCount - 1));
                        failed.set(true);
                    }
                    return null;
                }
            }, stackBuffer);

            namedMapOfFilers.read(FilerIO.intBytes(c), "filer2".getBytes(), (c + "rewrite").getBytes(), (monkey, filer, stackBuffer1, lock) -> {
                synchronized (lock) {
                    long v = FilerIO.readLong(filer, "value", stackBuffer1);
                    //System.out.println("RR:" + v + " from " + filer.getChunkFP());
                    if (v != addCount - 1) {
                        System.out.println("filerReadRewrite FAILED. " + v + " vs " + (addCount - 1));
                        failed.set(true);
                    }
                    return null;
                }
            }, stackBuffer);
        }
        Assert.assertFalse(failed.get());
    }

}
