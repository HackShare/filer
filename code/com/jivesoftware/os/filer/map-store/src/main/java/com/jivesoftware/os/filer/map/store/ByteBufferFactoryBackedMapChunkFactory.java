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
package com.jivesoftware.os.filer.map.store;

import com.jivesoftware.os.filer.io.ByteBufferFactory;

/**
 *
 * @author jonathan.colt
 */
public class ByteBufferFactoryBackedMapChunkFactory implements MapChunkFactory {

    private static final byte[] EMPTY_ID = new byte[16];

    private final int keySize;
    private final int payloadSize;
    private final int initialPageCapacity;
    private final ByteBufferFactory byteBufferFactory;

    public ByteBufferFactoryBackedMapChunkFactory(int keySize,
            int payloadSize,
            int initialPageCapacity, ByteBufferFactory byteBufferFactory) {
        this.keySize = keySize;
        this.payloadSize = payloadSize;
        this.initialPageCapacity = initialPageCapacity;
        this.byteBufferFactory = byteBufferFactory;
    }

    @Override
    public MapChunk getOrCreate(MapStore mapStore, String pageId) throws Exception {
        MapChunk set = mapStore.allocate((byte) 0, (byte) 0, EMPTY_ID, 0, initialPageCapacity, keySize,
                payloadSize,
                byteBufferFactory);
        return set;
    }

    @Override
    public MapChunk resize(MapStore mapStore, MapChunk chunk, String pageId, int newSize) throws Exception {
        MapChunk newChunk =  mapStore.allocate((byte) 0, (byte) 0, EMPTY_ID, 0, newSize, keySize,
                payloadSize,
                byteBufferFactory);
        mapStore.copyTo(chunk, newChunk, null);
        return newChunk;
    }

    @Override
    public MapChunk get(MapStore mapStore, String pageId) throws Exception {
        return null; // Since this impl doesn't perist there is nothing to get.
    }

}