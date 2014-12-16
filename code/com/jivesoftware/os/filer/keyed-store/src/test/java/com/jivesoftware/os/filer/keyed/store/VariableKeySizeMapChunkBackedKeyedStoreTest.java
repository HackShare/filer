package com.jivesoftware.os.filer.keyed.store;

import com.google.common.base.Charsets;
import com.jivesoftware.os.filer.chunk.store.ChunkFiler;
import com.jivesoftware.os.filer.chunk.store.ChunkStoreInitializer;
import com.jivesoftware.os.filer.chunk.store.MultiChunkStoreConcurrentFilerFactory;
import com.jivesoftware.os.filer.chunk.store.MultiChunkStoreInitializer;
import com.jivesoftware.os.filer.io.ByteArrayStripingLocksProvider;
import com.jivesoftware.os.filer.io.ByteBufferBackedFiler;
import com.jivesoftware.os.filer.io.ConcurrentFilerProvider;
import com.jivesoftware.os.filer.io.FileBackedMemMappedByteBufferFactory;
import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.FilerTransaction;
import com.jivesoftware.os.filer.map.store.ConcurrentFilerProviderBackedMapChunkProvider;
import com.jivesoftware.os.filer.map.store.FileBackedMapChunkProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.apache.commons.math.util.MathUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 *
 */
public class VariableKeySizeMapChunkBackedKeyedStoreTest {

    private String[] mapDirectories;
    private String[] chunkDirectories;

    @BeforeMethod
    public void setUp() throws Exception {
        mapDirectories = new String[] {
            Files.createTempDirectory(getClass().getSimpleName()).toFile().getAbsolutePath(),
            Files.createTempDirectory(getClass().getSimpleName()).toFile().getAbsolutePath()
        };
        chunkDirectories = new String[] {
            Files.createTempDirectory(getClass().getSimpleName()).toFile().getAbsolutePath(),
            Files.createTempDirectory(getClass().getSimpleName()).toFile().getAbsolutePath()
        };
    }

    private String[] buildMapDirectories(String[] baseMapDirectories, int keySize) {
        String[] mapDirectories = new String[baseMapDirectories.length];
        for (int basePathIndex = 0; basePathIndex < baseMapDirectories.length; basePathIndex++) {
            mapDirectories[basePathIndex] = new File(baseMapDirectories[basePathIndex], String.valueOf(keySize)).getAbsolutePath();
        }
        return mapDirectories;
    }

    @Test
    public void testFilerAutoCreate() throws Exception {
        final int[] keySizeThresholds = new int[] { 4, 16, 64, 256, 1024 };
        int chunkStoreCapacityInBytes = 30 * 1024 * 1024;
        MultiChunkStoreConcurrentFilerFactory multiChunkStore = new MultiChunkStoreInitializer(new ChunkStoreInitializer()).initializeMultiFileBacked(
            chunkDirectories, "data", 4, chunkStoreCapacityInBytes, false, 8, new ByteArrayStripingLocksProvider(64));
        long newFilerInitialCapacity = 512;
        VariableKeySizeMapChunkBackedKeyedStore.Builder<ByteBufferBackedFiler> builder =
            new VariableKeySizeMapChunkBackedKeyedStore.Builder<>();

        for (int keySize : keySizeThresholds) {
            FileBackedMapChunkProvider mapChunkFactory = new FileBackedMapChunkProvider(keySize, true, 8, false, 512,
                buildMapDirectories(mapDirectories, keySize), 4);
            builder.add(keySize, new PartitionedMapChunkBackedKeyedStore<>(mapChunkFactory, multiChunkStore));
        }

        VariableKeySizeMapChunkBackedKeyedStore<ByteBufferBackedFiler> keyedStore = builder.build();

        for (final int keySize : keySizeThresholds) {
            keyedStore.execute(keyOfLength(keySize), newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
                @Override
                public Void commit(Filer filer) throws IOException {
                    filer.seek(0);
                    FilerIO.writeInt(filer, keySize, "keySize");
                    return null;
                }
            });
        }

        for (final int keySize : keySizeThresholds) {
            keyedStore.execute(keyOfLength(keySize), -1, new FilerTransaction<Filer, Void>() {
                @Override
                public Void commit(Filer filer) throws IOException {
                    filer.seek(0);
                    int actual = FilerIO.readInt(filer, "keySize");
                    Assert.assertEquals(actual, keySize);
                    return null;
                }
            });
        }
    }

    @Test(expectedExceptions = IndexOutOfBoundsException.class, description = "Out of scope")
    public void testFilerGrowsCapacity() throws Exception {
        final int[] keySizeThresholds = new int[] { 4, 16 };
        int chunkStoreCapacityInBytes = 30 * 1024 * 1024;
        int newFilerInitialCapacity = 512;
        MultiChunkStoreConcurrentFilerFactory multiChunkStore = new MultiChunkStoreInitializer(new ChunkStoreInitializer()).initializeMultiFileBacked(
            chunkDirectories, "data", 4, chunkStoreCapacityInBytes, false, 8, new ByteArrayStripingLocksProvider(64));
        VariableKeySizeMapChunkBackedKeyedStore.Builder<ByteBufferBackedFiler> builder =
            new VariableKeySizeMapChunkBackedKeyedStore.Builder<>();

        for (int keySize : keySizeThresholds) {
            FileBackedMapChunkProvider mapChunkFactory = new FileBackedMapChunkProvider(keySize, true, 8, false, 512,
                buildMapDirectories(mapDirectories, keySize), 4);
            builder.add(keySize, new PartitionedMapChunkBackedKeyedStore<>(mapChunkFactory, multiChunkStore));
        }

        VariableKeySizeMapChunkBackedKeyedStore keyedStore = builder.build();

        int numberOfIntsInInitialCapacity = newFilerInitialCapacity / 4;
        int numberOfIntsInActualCapacity = numberOfIntsInInitialCapacity * 2; // actual capacity is doubled
        int numberOfTimesToGrow = 3;
        final int totalNumberOfInts = numberOfIntsInActualCapacity * MathUtils.pow(2, numberOfTimesToGrow - 1);

        for (int keySize : keySizeThresholds) {
            keyedStore.execute(keyOfLength(keySize), newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
                @Override
                public Void commit(Filer filer) throws IOException {
                    filer.seek(0);
                    for (int i = 0; i < totalNumberOfInts; i++) {
                        FilerIO.writeInt(filer, i, String.valueOf(i));
                    }
                    return null;
                }
            });
        }

        for (int keySize : keySizeThresholds) {
            keyedStore.execute(keyOfLength(keySize), newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
                @Override
                public Void commit(Filer filer) throws IOException {
                    filer.seek(0);
                    for (int i = 0; i < totalNumberOfInts; i++) {
                        int actual = FilerIO.readInt(filer, String.valueOf(i));
                        Assert.assertEquals(actual, i);
                    }
                    return null;
                }
            });
        }
    }

    @Test
    public void testChunkBacked() throws Exception {
        final int[] keySizeThresholds = new int[] { 4, 16, 64, 256, 1024 };
        int chunkStoreCapacityInBytes = 30 * 1024 * 1024;

        File mapDir = Files.createTempDirectory("map").toFile();
        FileBackedMemMappedByteBufferFactory byteBufferFactory = new FileBackedMemMappedByteBufferFactory(mapDir);
        MultiChunkStoreConcurrentFilerFactory multiChunkStore = new MultiChunkStoreInitializer(new ChunkStoreInitializer())
            .initializeMultiByteBufferBacked("boo", byteBufferFactory, 10, chunkStoreCapacityInBytes, true, 10, new ByteArrayStripingLocksProvider(10));

        long newFilerInitialCapacity = 4_000;
        VariableKeySizeMapChunkBackedKeyedStore.Builder<ChunkFiler> builder =
            new VariableKeySizeMapChunkBackedKeyedStore.Builder<>();

        for (int keySize : keySizeThresholds) {
            @SuppressWarnings("unchecked")
            ConcurrentFilerProvider<ChunkFiler>[] concurrentFilerProviders = new ConcurrentFilerProvider[] {
                new ConcurrentFilerProvider<>(("booya-map-1-" + keySize).getBytes(), multiChunkStore),
                new ConcurrentFilerProvider<>(("booya-map-2-" + keySize).getBytes(), multiChunkStore)
            };
            ConcurrentFilerProviderBackedMapChunkProvider<ChunkFiler> mapChunkFactory = new ConcurrentFilerProviderBackedMapChunkProvider<>(
                keySize, true, 8, false, 512,
                concurrentFilerProviders);

            builder.add(keySize, new PartitionedMapChunkBackedKeyedStore<>(mapChunkFactory, multiChunkStore));
        }

        VariableKeySizeMapChunkBackedKeyedStore<ChunkFiler> keyedStore = builder.build();

        for (final int keySize : keySizeThresholds) {
            keyedStore.execute(keyOfLength(keySize), newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
                @Override
                public Void commit(Filer filer) throws IOException {
                    filer.seek(0);
                    for (int i = 0; i < 1_000; i++) {
                        FilerIO.writeInt(filer, keySize, "keySize");
                    }
                    return null;
                }
            });
        }

        for (final int keySize : keySizeThresholds) {
            keyedStore.execute(keyOfLength(keySize), -1, new FilerTransaction<Filer, Void>() {
                @Override
                public Void commit(Filer filer) throws IOException {
                    filer.seek(0);
                    for (int i = 0; i < 1_000; i++) {
                        int actual = FilerIO.readInt(filer, "keySize");
                        Assert.assertEquals(actual, keySize);
                    }
                    return null;
                }
            });
        }
    }

    private byte[] keyOfLength(int length) {
        StringBuilder buf = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            buf.append('a');
        }
        return buf.toString().getBytes(Charsets.US_ASCII);
    }
}
