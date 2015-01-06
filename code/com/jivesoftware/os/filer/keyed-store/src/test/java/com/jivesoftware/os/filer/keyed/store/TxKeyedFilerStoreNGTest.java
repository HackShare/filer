/*
 * $Revision$
 * $Date$
 *
 * Copyright (C) 1999-$year$ Jive Software. All rights reserved.
 *
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package com.jivesoftware.os.filer.keyed.store;

import com.jivesoftware.os.filer.chunk.store.ChunkStore;
import com.jivesoftware.os.filer.chunk.store.ChunkStoreInitializer;
import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.FilerTransaction;
import com.jivesoftware.os.filer.io.IBA;
import com.jivesoftware.os.filer.io.RewriteFilerTransaction;
import com.jivesoftware.os.filer.map.store.api.KeyValueStore;
import java.io.IOException;
import java.nio.file.Files;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * @author jonathan.colt
 */
public class TxKeyedFilerStoreNGTest {

    @Test
    public void keyedStoreTest() throws Exception {
        String[] chunkPaths = new String[]{Files.createTempDirectory("testNewChunkStore")
            .toFile()
            .getAbsolutePath()};
        ChunkStore chunkStore1 = new ChunkStoreInitializer().initialize(chunkPaths, "data1", 0, 10, true, 8);
        ChunkStore chunkStore2 = new ChunkStoreInitializer().initialize(chunkPaths, "data2", 0, 10, true, 8);
        ChunkStore[] chunkStores = new ChunkStore[]{chunkStore1, chunkStore2};

        TxKeyedFilerStore store = new TxKeyedFilerStore(chunkStores,
            "booya".getBytes());

        int newFilerInitialCapacity = 512;

        byte[] key = FilerIO.intBytes(1010);
        store.execute(key, newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
            @Override
            public Void commit(Filer filer) throws IOException {
                FilerIO.writeInt(filer, 10, "");
                return null;
            }
        });

        store.execute(key, newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
            @Override
            public Void commit(Filer filer) throws IOException {
                filer.seek(0);
                int ten = FilerIO.readInt(filer, "");
                System.out.println("ten:" + ten);
                assertEquals(ten, 10);
                return null;
            }
        });
    }

    @Test
    public void rewriteTest() throws Exception {
        String[] chunkPaths = new String[]{Files.createTempDirectory("testNewChunkStore")
            .toFile()
            .getAbsolutePath()};
        ChunkStore chunkStore1 = new ChunkStoreInitializer().initialize(chunkPaths, "data1", 0, 10, true, 8);
        ChunkStore chunkStore2 = new ChunkStoreInitializer().initialize(chunkPaths, "data2", 0, 10, true, 8);
        ChunkStore[] chunkStores = new ChunkStore[]{chunkStore1, chunkStore2};

        TxKeyedFilerStore store = new TxKeyedFilerStore(chunkStores,
            "booya".getBytes());

        int newFilerInitialCapacity = 512;

        final byte[] key = FilerIO.intBytes(1020);
        store.execute(key, newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
            @Override
            public Void commit(Filer filer) throws IOException {
                filer.seek(0);
                FilerIO.writeInt(filer, 10, "");
                return null;
            }
        });
        store.executeRewrite(key, newFilerInitialCapacity, new RewriteFilerTransaction<Filer, Void>() {
            @Override
            public Void commit(Filer currentFiler, Filer newFiler) throws IOException {
                currentFiler.seek(0);
                int ten = FilerIO.readInt(currentFiler, "");
                assertEquals(ten, 10);

                newFiler.seek(0);
                FilerIO.writeInt(newFiler, 20, "");
                return null;
            }
        });

        store = new TxKeyedFilerStore(chunkStores,
            "booya".getBytes());

        store.execute(key, newFilerInitialCapacity, new FilerTransaction<Filer, Void>() {
            @Override
            public Void commit(Filer filer) throws IOException {
                filer.seek(0);
                int twenty = FilerIO.readInt(filer, "");
                assertEquals(twenty, 20);
                return null;
            }
        });

        store.stream(new KeyValueStore.EntryStream<IBA, Filer>() {

            @Override
            public boolean stream(IBA k, Filer filer) throws IOException {
                assertEquals(k.getBytes(), key);
                filer.seek(0);
                int twenty = FilerIO.readInt(filer, "");
                assertEquals(twenty, 20);
                return true;
            }
        });

        store.streamKeys(new KeyValueStore.KeyStream<IBA>() {

            @Override
            public boolean stream(IBA k) throws IOException {
                assertEquals(k.getBytes(), key);
               return true;
            }
        });
    }
}