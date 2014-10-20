package com.jivesoftware.os.filer.map.store;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.jivesoftware.os.filer.io.ByteBufferFactory;
import com.jivesoftware.os.filer.io.KeyValueMarshaller;
import com.jivesoftware.os.filer.map.store.api.KeyValueStore;
import com.jivesoftware.os.filer.map.store.api.KeyValueStoreException;
import java.util.Iterator;
import java.util.List;

public abstract class VariableKeySizeBytesObjectMapStore<K, V> implements KeyValueStore<K, V> {

    private final int[] keySizeThresholds;
    private final BytesObjectMapStore<K, V>[] mapStores;
    
    @SuppressWarnings("unchecked")
    public VariableKeySizeBytesObjectMapStore(int[] keySizeThresholds, int initialPageCapacity, V returnWhenGetReturnsNull,
            ByteBufferFactory byteBufferFactory, KeyValueMarshaller<K, V> keyValueMarshaller) {

        this.keySizeThresholds = keySizeThresholds;
        this.mapStores = new BytesObjectMapStore[keySizeThresholds.length];
        
        for (int i = 0; i < keySizeThresholds.length; i++) {
            Preconditions.checkArgument(i == 0 || keySizeThresholds[i] > keySizeThresholds[i - 1], "Thresholds must be monotonically increasing");

            final int keySize = keySizeThresholds[i];
            mapStores[i] = new BytesObjectMapStore<>(keySize, true, initialPageCapacity, returnWhenGetReturnsNull, byteBufferFactory, keyValueMarshaller);
        }
    }

    private BytesObjectMapStore<K, V> getMapStore(int keyLength) {
        for (int i = 0; i < keySizeThresholds.length; i++) {
            if (keySizeThresholds[i] >= keyLength) {
                return mapStores[i];
            }
        }
        throw new IndexOutOfBoundsException("Key is too long");
    }

    protected abstract int keyLength(K key);

   

    @Override
    public void add(K key, V value) throws KeyValueStoreException {
        getMapStore(keyLength(key)).add(key, value);
    }

    @Override
    public void remove(K key) throws KeyValueStoreException {
        getMapStore(keyLength(key)).remove(key);
    }

    @Override
    public V get(K key) throws KeyValueStoreException {
        return getMapStore(keyLength(key)).get(key);
    }

    @Override
    public V getUnsafe(K key) throws KeyValueStoreException {
        return getMapStore(keyLength(key)).getUnsafe(key);
    }

    @Override
    public long estimateSizeInBytes() throws Exception {
        long estimate = 0;
        for (BytesObjectMapStore<K, V> mapStore : mapStores) {
            estimate += mapStore.estimateSizeInBytes();
        }
        return estimate;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        List<Iterator<Entry<K, V>>> iterators = Lists.newArrayListWithCapacity(mapStores.length);
        for (BytesObjectMapStore<K, V> mapStore : mapStores) {
            iterators.add(mapStore.iterator());
        }
        return Iterators.concat(iterators.iterator());
    }
}