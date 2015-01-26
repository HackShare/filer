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
import com.jivesoftware.os.filer.io.ByteArrayStripingLocksProvider;
import com.jivesoftware.os.filer.io.OpenFiler;
import com.jivesoftware.os.filer.map.store.LexSkipListComparator;
import com.jivesoftware.os.filer.map.store.MapContext;
import com.jivesoftware.os.filer.map.store.MapStore;
import com.jivesoftware.os.filer.map.store.SkipListMapContext;
import com.jivesoftware.os.filer.map.store.SkipListMapStore;
import java.io.IOException;
import java.util.Arrays;

/**
 * @author jonathan.colt
 */
public class SkipListMapBackedKeyedFPIndexOpener implements OpenFiler<SkipListMapBackedKeyedFPIndex, ChunkFiler> {

    private final ByteArrayStripingLocksProvider keyLocks;

    public SkipListMapBackedKeyedFPIndexOpener(ByteArrayStripingLocksProvider keyLocks) {
        this.keyLocks = keyLocks;
    }

    @Override
    public SkipListMapBackedKeyedFPIndex open(ChunkFiler filer) throws IOException {
        MapContext mapContext = MapStore.INSTANCE.open(filer);
        byte[] headKey = new byte[mapContext.keySize];
        Arrays.fill(headKey, Byte.MIN_VALUE);
        SkipListMapContext skipListMapContext = SkipListMapStore.INSTANCE.open(headKey, LexSkipListComparator.cSingleton, filer);
        return new SkipListMapBackedKeyedFPIndex(filer.getChunkStore(), filer.getChunkFP(), skipListMapContext, this, keyLocks);
    }

}