package com.jivesoftware.os.filer.map.store;

import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.map.store.api.KeyRange;
import java.io.IOException;
import java.util.List;

/**
 * This is a skip list which is backed by a byte[]. This collection wastes quite a bit of space in favor of page in and out speed. May make sense to use a
 * compression strategy when sending over the wire. Each entry you sadd cost a fixed amount of space. This can be calculate by the following: entrySize =
 * entrySize+(1+(entrySize*maxColumHeight); maxColumHeight = ? where 2 ^ ? is > maxCount
 *
 * The key composed of all BYTE.MIN_VALUE is typically reserved as the head of the list.
 *
 * @author jonathan
 */
public class SkipListMapStore {

    static public final SkipListMapStore INSTANCE = new SkipListMapStore();

    private SkipListMapStore() {
    }

    private static final int cColumKeySize = 4; // stores the int index of the key it points to !! could make dynamic to save space

    public long computeFilerSize(
        int maxCount,
        int keySize,
        boolean variableKeySizes,
        int payloadSize,
        byte maxColumnHeight) throws IOException {
        maxCount += 2; // room for a head key and a tail key
        payloadSize = payloadSize(maxColumnHeight) + payloadSize;
        return MapStore.INSTANCE.computeFilerSize(maxCount, keySize, variableKeySizes, payloadSize, false);
    }

    /**
     *
     * @param maxHeight
     * @return
     */
    int payloadSize(byte maxHeight) {
        return 1 + (cColumKeySize * maxHeight);
    }

    public SkipListMapContext create(
        int _maxCount,
        byte[] headKey,
        int keySize,
        boolean variableKeySizes,
        int _payloadSize,
        byte maxColumnHeight,
        SkipListComparator _valueComparator,
        Filer filer) throws IOException {
        if (headKey.length != keySize) {
            throw new RuntimeException("Expected that headKey.length == keySize");
        }
        _maxCount += 2;
        int columnAndPayload = payloadSize(maxColumnHeight) + _payloadSize;
        MapContext mapContext = MapStore.INSTANCE.create(_maxCount, keySize, variableKeySizes, columnAndPayload, false, filer);
        int headKeyIndex = (int) MapStore.INSTANCE.add(filer, mapContext, (byte) 1, headKey,
            newColumn(new byte[_payloadSize], maxColumnHeight, maxColumnHeight));
        SkipListMapContext context = new SkipListMapContext(mapContext, headKeyIndex, headKey, _valueComparator);
        return context;
    }

    public SkipListMapContext open(byte[] headKey,
        SkipListComparator _valueComparator,
        Filer filer) throws IOException {

        MapContext mapContext = MapStore.INSTANCE.open(filer);
        int headKeyIndex = (int) MapStore.INSTANCE.get(filer, mapContext, headKey);
        if (headKeyIndex == -1) {
            throw new RuntimeException("SkipListSetPage:Invalid Page!");
        }
        SkipListMapContext context = new SkipListMapContext(mapContext, headKeyIndex, headKey, _valueComparator);
        return context;
    }

    /**
     *
     * @param page
     * @return
     */
    public long getCount(Filer filer, SkipListMapContext page) throws IOException {
        return MapStore.INSTANCE.getCount(filer) - 1; // -1 because of head
    }

    /**
     *
     * @param filer
     * @param context
     * @param key
     * @param _payload
     * @throws java.io.IOException
     */
    public void add(Filer filer, SkipListMapContext context, byte[] key, byte[] _payload) throws IOException {

        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index != -1) { // aready exists so just update payload
            MapStore.INSTANCE.setPayloadAtIndex(filer, context.mapContext, index, columnSize(context.maxHeight), _payload, 0, _payload.length);
            return;
        }

        byte[] newColumn = newColumn(_payload, context.maxHeight, (byte) -1); // create a new colum for a new key
        int insertsIndex = (int) MapStore.INSTANCE.add(filer, context.mapContext, (byte) 1, key, newColumn);

        int level = context.maxHeight - 1;
        int ilevel = columnLength(filer, context, insertsIndex);
        int atIndex = context.headIndex;
        while (level > 0) {
            int nextIndex = rcolumnLevel(filer, context, atIndex, level);
            if (nextIndex == -1) {
                if (level < ilevel) {
                    wcolumnLevel(filer, context, atIndex, level, insertsIndex);
                    if (level == 1) {
                        wcolumnLevel(filer, context, insertsIndex, 0, atIndex);
                    }
                }
                level--;
            } else {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, nextIndex),
                    MapStore.INSTANCE.getKey(filer, context.mapContext, insertsIndex));
                if (compare == 0) {
                    throw new RuntimeException("should be impossible");
                } else if (compare < 0) { // keep looking forward
                    atIndex = nextIndex;
                } else { // insert
                    if (level < ilevel) {
                        wcolumnLevel(filer, context, insertsIndex, level, nextIndex);
                        wcolumnLevel(filer, context, atIndex, level, insertsIndex);
                        if (level == 1) {
                            wcolumnLevel(filer, context, insertsIndex, 0, atIndex);
                            wcolumnLevel(filer, context, nextIndex, 0, insertsIndex);
                        }
                    }
                    level--;
                }
            }
        }
    }

    /**
     * !! un-tested
     *
     * @param context
     * @param _key
     * @return
     */
    public byte[] findWouldInsertAtOrAfter(Filer filer, SkipListMapContext context, byte[] _key) throws IOException {

        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, _key);
        if (index != -1) { // aready exists so return self
            return _key;
        }
        // create a new colum for a new key

        int level = context.maxHeight - 1;
        int atIndex = context.headIndex;
        while (level > 0) {
            int nextIndex = rcolumnLevel(filer, context, atIndex, level);
            if (nextIndex == -1) {
                if (level == 1) {
                    return MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, atIndex);
                }
                level--;
            } else {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, nextIndex),
                    _key);
                if (compare == 0) {
                    throw new RuntimeException("should be impossible");
                } else if (compare < 0) { // keep looking forward
                    atIndex = nextIndex;
                } else { // insert
                    if (level == 1) {
                        if (atIndex == context.headIndex) {
                            return null;
                        }
                        return MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, atIndex);
                    }
                    level--;
                }
            }
        }
        return null;
    }

    /**
     *
     * @param page
     * @return
     */
    public byte[] getFirst(Filer filer, SkipListMapContext page) throws IOException {
        int firstIndex = rcolumnLevel(filer, page, page.headIndex, 1);
        if (firstIndex == -1) {
            return null;
        } else {
            return MapStore.INSTANCE.getKeyAtIndex(filer, page.mapContext, firstIndex);
        }
    }

    /**
     *
     * @param context
     * @param _key
     */
    public void remove(Filer filer, SkipListMapContext context, byte[] _key) throws IOException {
        if (_key == null || _key.length == 0) {
            throw new RuntimeException("null not supported");
        }
        int removeIndex = (int) MapStore.INSTANCE.get(filer, context.mapContext, _key);
        if (removeIndex == -1) { // doesn't exists so return
            return;
        }
        int sEntrySize = context.mapContext.entrySize;

        int level = context.maxHeight - 1;
        int atIndex = context.headIndex;
        while (level > 0) {
            int nextIndex = rcolumnLevel(filer, context, atIndex, level);
            if (nextIndex == -1) {
                level--;
            } else {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, nextIndex),
                    MapStore.INSTANCE.getKey(filer, context.mapContext, removeIndex));
                if (compare == 0) {
                    while (level > -1) {
                        int removesNextIndex = rcolumnLevel(filer, context, removeIndex, level);
                        wcolumnLevel(filer, context, atIndex, level, removesNextIndex);
                        if (level == 0) {
                            wcolumnLevel(filer, context, removesNextIndex, level, atIndex);
                        }
                        level--;
                    }

                } else if (compare < 0) {
                    atIndex = nextIndex;
                } else {
                    level--;
                }
            }
        }
        MapStore.INSTANCE.remove(filer, context.mapContext, _key);
    }

    /**
     *
     * @param context
     * @param key
     * @return
     */
    public byte[] getPrior(Filer filer, SkipListMapContext context, byte[] key) throws IOException {
        if (key == null || key.length == 0) {
            return null;
        }
        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index == -1) {
            return null;
        } else {
            int pi = rcolumnLevel(filer, context, index, 0);
            if (pi == -1) {
                return null;
            } else {
                byte[] got = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, pi);
                if (isHeadKey(context.headKey, got)) {
                    return null; // don't give out head key
                }
                return got;
            }
        }
    }

    public boolean isHeadKey(byte[] headKey, byte[] bytes) {
        if (bytes.length != headKey.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != headKey[i]) {
                return false;
            }
        }
        return true;
    }

    public byte[] getNextKey(Filer filer, SkipListMapContext context, byte[] key) throws IOException {
        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index == -1) {
            return null;
        } else {
            int nextIndex = rcolumnLevel(filer, context, index, 1);
            if (nextIndex == -1) {
                return null;
            } else {
                return MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, nextIndex);
            }
        }
    }

    public byte[] getExistingPayload(Filer filer, SkipListMapContext context, byte[] key) throws IOException {
        int index = (int) MapStore.INSTANCE.get(filer, context.mapContext, key);
        if (index == -1) {
            return null;
        } else {
            return getColumnPayload(filer, context, index, context.maxHeight);
        }
    }

    /**
     * DO NOT cache KeyPayload... It will be reused by PSkipListSet
     *
     */
    public void getSlice(Filer filer, SkipListMapContext context, byte[] from, byte[] to, int _max, SliceStream _get)
        throws IOException {
        final KeyPayload sent = new KeyPayload(null, null);
        int at;
        if (from != null && from.length > 0) {
            at = (int) MapStore.INSTANCE.get(filer, context.mapContext, from);
            if (at == -1) {
                byte[] found = findWouldInsertAtOrAfter(filer, context, from);
                if (found == null) {
                    _get.stream(null); // done cause from doesn't exist
                    return;
                }
                at = (int) MapStore.INSTANCE.get(filer, context.mapContext, found);
                at = rcolumnLevel(filer, context, at, 1); // move to next because we found the one before
            }

        } else {
            at = context.headIndex;
        }

        done:
        while (at != -1) {
            if (to != null) {
                int compare = context.keyComparator.compare(MapStore.INSTANCE.getKey(filer, context.mapContext, at),
                    to);
                if (compare > 0) {
                    break;
                }
            }
            byte[] key = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, at);
            byte[] payload = getColumnPayload(filer, context, at, context.maxHeight);
            sent.key = key;
            sent.payload = payload;
            if (_get.stream(sent) != sent) {
                break;
            }
            at = rcolumnLevel(filer, context, at, 1);
            if (at == -1) {
                break;
            }
            if (_max > 0) {
                _max--;
                if (_max <= 0) {
                    break;
                }
            }
        }
        _get.stream(null);
    }

    public boolean streamKeys(final Filer filer, final SkipListMapContext context, final Object lock,
        List<KeyRange> ranges, MapStore.KeyStream stream) throws IOException {
        if (ranges == null) {
            for (int index = 0; index < context.mapContext.capacity; index++) {
                if (index == context.headIndex) { // Barf
                    continue;
                }
                byte[] key;
                synchronized (lock) {
                    key = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, index);
                }
                if (key != null) {
                    if (!stream.stream(key)) {
                        return false;
                    }
                }
            }

        } else {
            for (KeyRange range : ranges) {
                byte[] key = findWouldInsertAtOrAfter(filer, context, range.getStartInclusiveKey());
                if (key != null) {
                    if (range.contains(key)) {
                        if (!stream.stream(key)) {
                            return false;
                        }
                    }
                    byte[] next = getNextKey(filer, context, key);
                    while (next != null && range.contains(next)) {
                        if (!stream.stream(next)) {
                            return false;
                        }
                        next = getNextKey(filer, context, next);
                    }
                }
            }

        }
        return true;
    }

    static public interface SliceStream<R> {

        KeyPayload stream(KeyPayload v) throws IOException;

    }

    /**
     * this is a the lazy impl... this can be highly optimized when we have time!
     *
     * @param from
     * @param to
     * @throws Exception
     */
    public void copyTo(Filer f, SkipListMapContext from, final Filer t, final SkipListMapContext to) throws IOException {
        getSlice(f, from, null, null, -1, new SliceStream() {

            @Override
            public KeyPayload stream(KeyPayload v) throws IOException {
                if (v == null) {
                    return v;
                }
                add(t, to, v.key, v.payload);
                return v;
            }
        });
    }

    private byte[] newColumn(byte[] _payload, int _maxHeight, byte _height) {
        if (_height <= 0) {
            byte newH = 2;
            while (Math.random() > 0.5d) { // could pick a rand number bewteen 1 and 32 instead
                if (newH + 1 >= _maxHeight) {
                    break;
                }
                newH++;
            }
            _height = newH;
        }
        byte[] column = new byte[1 + (_maxHeight * cColumKeySize) + _payload.length];
        column[0] = _height;
        for (int i = 0; i < _maxHeight; i++) {
            setColumKey(column, i, FilerIO.intBytes(-1)); // fill with nulls ie -1
        }
        System.arraycopy(_payload, 0, column, 1 + (_maxHeight * cColumKeySize), _payload.length);
        return column;
    }

    private void setColumKey(byte[] _column, int _h, byte[] _key) {
        System.arraycopy(_key, 0, _column, 1 + (_h * cColumKeySize), cColumKeySize);
    }

    private byte columnLength(Filer f, SkipListMapContext context, int setIndex) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        return MapStore.INSTANCE.read(f, MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize));
    }

    private int rcolumnLevel(Filer f, SkipListMapContext context, int setIndex, int level) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        int offset = (int) MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize) + 1 + (level * cColumKeySize);
        return MapStore.INSTANCE.readInt(f, offset);
    }

    private void wcolumnLevel(Filer f, SkipListMapContext context, int setIndex, int level, int v) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        int offset = (int) MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize) + 1 + (level * cColumKeySize);
        MapStore.INSTANCE.writeInt(f, offset, v);
    }

    private int columnSize(int maxHeight) {
        return 1 + (cColumKeySize * maxHeight);
    }

    private byte[] getColumnPayload(Filer f, SkipListMapContext context, int setIndex, int maxHeight) throws IOException {
        int entrySize = context.mapContext.entrySize;
        int keyLength = context.mapContext.keyLengthSize;
        int keySize = context.mapContext.keySize;
        int startOfPayload = (int) MapStore.INSTANCE.startOfPayload(setIndex, entrySize, keyLength, keySize);
        int size = context.mapContext.payloadSize - columnSize(maxHeight);
        byte[] payload = new byte[size];
        MapStore.INSTANCE.read(f, startOfPayload + 1 + (maxHeight * cColumKeySize), payload, 0, size);
        return payload;
    }

    public void toSysOut(Filer f, SkipListMapContext context, BytesToString keyToString) throws IOException {
        if (keyToString == null) {
            keyToString = new BytesToBytesString();
        }
        int atIndex = context.headIndex;
        int count = 0;
        while (atIndex != -1) {
            toSysOut(f, context, atIndex, keyToString);
            atIndex = rcolumnLevel(f, context, atIndex, 1);

            if (count > MapStore.INSTANCE.getCount(f)) {
                System.out.println("BAD Panda! Cyclic");
                break;
            }
            count++;
        }
    }

    /**
     *
     */
    static abstract public class BytesToString {

        /**
         *
         * @param bytes
         * @return
         */
        abstract public String bytesToString(byte[] bytes);
    }

    /**
     *
     */
    static public class BytesToDoubleString extends BytesToString {

        /**
         *
         * @param bytes
         * @return
         */
        @Override
        public String bytesToString(byte[] bytes) {
            return Double.toString(FilerIO.bytesDouble(bytes));
        }
    }

    /**
     *
     */
    static public class BytesToBytesString extends BytesToString {

        /**
         *
         * @param bytes
         * @return
         */
        @Override
        public String bytesToString(byte[] bytes) {
            return new String(bytes); // UString.toString(bytes, ",");
        }
    }

    private void toSysOut(Filer filer, SkipListMapContext context, int index, BytesToString keyToString) throws IOException {
        byte[] key = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, index);
        System.out.print("\ti:" + index);
        System.out.print("\tv:" + keyToString.bytesToString(key) + " - \t");
        int l = columnLength(filer, context, index);
        for (int i = 0; i < l; i++) {
            if (i != 0) {
                System.out.print("),\t" + i + ":(");
            } else {
                System.out.print(i + ":(");
            }
            int ni = rcolumnLevel(filer, context, index, i);
            if (ni == -1) {
                System.out.print("NULL");

            } else {
                byte[] nkey = MapStore.INSTANCE.getKeyAtIndex(filer, context.mapContext, ni);
                if (nkey == null) {
                    System.out.print(ni + "=???");
                } else {
                    System.out.print(ni + "=" + keyToString.bytesToString(nkey));
                }

            }
        }
        System.out.println(")");
    }
}