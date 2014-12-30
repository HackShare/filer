package com.jivesoftware.os.filer.map.store;

import com.jivesoftware.os.filer.io.ByteBufferBackedFiler;
import com.jivesoftware.os.filer.io.ByteBufferFactory;
import com.jivesoftware.os.filer.io.Filer;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.HeapByteBufferFactory;
import com.jivesoftware.os.filer.map.store.extractors.IndexStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * @author jonathan
 */
public class SkipListSet_Test {

    /**
     * @param _args
     */
    public static void main(String[] _args) throws IOException {

        HeapByteBufferFactory provider = new HeapByteBufferFactory();

        //chart(factory);
        //System.exit(0);
        int it = 30;
        test(it, 2, it, provider);
        System.exit(0);

        final long seed = System.currentTimeMillis();
        final int keySize = 1;
        final int payloadSize = 2;
        it = 150;

        final MapStore pset = MapStore.INSTANCE;

        final int _it = it;
        int filerSize = pset.computeFilerSize(_it, keySize, false, payloadSize, false);
        Filer filer = new ByteBufferBackedFiler(provider.allocate("booya".getBytes(), filerSize));

        SkipListSet sls = new SkipListSet();
        byte[] headKey = new byte[]{Byte.MIN_VALUE};

        SkipListSetPage slsp = sls.create(_it, headKey, keySize, false, payloadSize, false, new SkipListComparator() {

            @Override
            public int compare(Filer a, int astart, Filer b, int bstart, int length) throws IOException {
                for (int i = 0; i < length; i++) {
                    byte av = pset.read(a, astart + i);
                    byte bv = pset.read(b, bstart + i);

                    if (av == bv) {
                        continue;
                    }
                    if (av < bv) {
                        return -1;
                    }
                    for (int j = i; j < length; j++) {
                        if (av < bv) {
                            return -1;
                        }
                    }
                    return 1;
                }
                return 0;
            }

            @Override
            public long range(byte[] a, byte[] b) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        }, filer);

        Random random = new Random(1_234);
        byte[] a = new byte[]{65}; //URandom.randomLowerCaseAlphaBytes(keySize);
        byte[] b = new byte[]{66}; //URandom.randomLowerCaseAlphaBytes(keySize);
        byte[] c = new byte[]{67}; //URandom.randomLowerCaseAlphaBytes(keySize);
        byte[] k4 = new byte[]{68}; //URandom.randomLowerCaseAlphaBytes(keySize);
        byte[] payload1 = TestUtils.randomLowerCaseAlphaBytes(random, payloadSize);

        int n = 1;
        n = p(n, c, sls, filer, slsp, payload1);
        n = p(n, a, sls, filer, slsp, payload1);
        n = p(n, b, sls, filer, slsp, payload1);

        //System.exit(0);
        //for(int i=0;i<1000;i++) {
        n = p(n, c, sls, filer, slsp, payload1);
        n = p(n, a, sls, filer, slsp, payload1);
        //n = m(n,b,sls);
        n = p(n, b, sls, filer, slsp, payload1);
        n = m(n, b, sls, filer, slsp);
        n = p(n, a, sls, filer, slsp, payload1);
        n = p(n, b, sls, filer, slsp, payload1);

        //}
        //System.exit(0);
        Object[] keys = new Object[]{a, b, c};
        for (int i = 0; i < 100_000; i++) {
            if (Math.random() < 0.5d) {
                byte[] ak = (byte[]) keys[random.nextInt(keys.length)];
                System.out.println("+" + new String(ak) + " " + sls.slgetCount(filer, slsp));
                sls.sladd(filer, slsp, ak, payload1);
            } else {
                byte[] rk = (byte[]) keys[random.nextInt(keys.length)];
                System.out.println("-" + new String(rk) + " " + sls.slgetCount(filer, slsp));
                sls.slremove(filer, slsp, rk);
            }
        }

        //System.exit(0);
        random = new Random(seed);
        for (int i = 0; i < _it; i++) {
            byte[] key = TestUtils.randomLowerCaseAlphaBytes(random, keySize);
            byte[] payload = TestUtils.randomLowerCaseAlphaBytes(random, payloadSize);
            //System.out.println("adding:"+new String(key));
            sls.sladd(filer, slsp, key, payload);
        }
        sls.sltoSysOut(filer, slsp, null);
        System.out.println("count:" + sls.slgetCount(filer, slsp));

        random = new Random(seed);
        for (int i = 0; i < _it; i++) {
            byte[] key = TestUtils.randomLowerCaseAlphaBytes(random, keySize);
            byte[] payload = TestUtils.randomLowerCaseAlphaBytes(random, payloadSize); // burns through random at the same rate as add
            //System.out.println("removing:"+new String(key));
            sls.slremove(filer, slsp, key);
            //sls.toSysOut();
        }
        //sls.toSysOut();
        if (sls.slgetCount(filer, slsp) != 0) {
            pset.get(filer, slsp.chunk, new IndexStream<Exception>() {

                @Override
                public boolean stream(long v) throws Exception {
                    if (v != -1) {
                        System.out.println(v);
                    }
                    return true;
                }
            });
        }
        //sls.toSysOut();
        System.out.println("count:" + sls.slgetCount(filer, slsp));
        random = new Random(seed);
        for (int i = 0; i < _it; i++) {
            byte[] key = TestUtils.randomLowerCaseAlphaBytes(random, keySize);
            byte[] payload = TestUtils.randomLowerCaseAlphaBytes(random, payloadSize); // burns through random at the same rate as add
            //System.out.println("removing:"+new String(key));
            if (i % 2 == 0) {
                //System.out.println("-"+sls.getCount());
                sls.slremove(filer, slsp, key);
            } else {
                //System.out.println("+"+sls.getCount());
                sls.sladd(filer, slsp, key, payload);
            }
            //sls.toSysOut();
        }
        System.out.println("count:" + sls.slgetCount(filer, slsp));

    }

    private static int p(int n, byte[] v, SkipListSet sls, Filer filer, SkipListSetPage slsp, byte[] p) throws IOException {
        System.out.println(n + ": add " + new String(v) + " " + sls.slgetCount(filer, slsp));
        sls.sladd(filer, slsp, v, p);
        sls.sltoSysOut(filer, slsp, null);
        return n + 1;
    }

    private static int m(int n, byte[] v, SkipListSet sls, Filer filer, SkipListSetPage slsp) throws IOException {
        System.out.println(n + ": remove " + new String(v) + " " + sls.slgetCount(filer, slsp));
        sls.slremove(filer, slsp, v);
        sls.sltoSysOut(filer, slsp, null);
        return n + 1;
    }

    private static Boolean test(final int _iterations, final int _keySize, final int _maxSize, HeapByteBufferFactory provider)
        throws IOException {
        final int payloadSize = 4;
        final MapStore pset = MapStore.INSTANCE;
        int filerSize = pset.computeFilerSize(_maxSize, _keySize, false, payloadSize, false);
        ByteBufferBackedFiler filer = new ByteBufferBackedFiler(provider.allocate("booya".getBytes(), filerSize));

        byte[] headKey = new byte[_keySize];
        Arrays.fill(headKey, Byte.MIN_VALUE);
        SkipListSet sls = new SkipListSet();
        SkipListSetPage slsp = sls.create(_maxSize, headKey, _keySize, false, payloadSize, false, new SkipListComparator() {

            @Override
            public int compare(Filer a, int astart, Filer b, int bstart, int length) throws IOException {
                for (int i = 0; i < length; i++) {
                    byte av = pset.read(a, astart + i);
                    byte bv = pset.read(b, bstart + i);
                    if (av == bv) {
                        continue;
                    }
                    if (av < bv) {
                        return -1;
                    }
                    for (int j = i; j < length; j++) {
                        if (av < bv) {
                            return -1;
                        }
                    }
                    return 1;
                }
                return 0;
            }

            @Override
            public long range(byte[] a, byte[] b) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        }, filer);

        System.out.println("MaxCount = " + pset.getMaxCount(filer) + " vs " + _iterations + " vs " + pset.getCapacity(filer));
        System.out.println("Upper Bound Max Count = " + pset.absoluteMaxCount(pset.getKeySize(filer), pset.getPayloadSize(filer)));
        long seed = System.currentTimeMillis();

        System.out.println("\nadd:");
        Random random = new Random(seed);
        long t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            sls.sladd(filer, slsp, TestUtils.randomLowerCaseAlphaBytes(random, _keySize), FilerIO.intBytes(i));
        }
        System.out.println("ByteSL add(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + sls.slgetCount(filer, slsp));

        random = new Random(seed);
        t = System.currentTimeMillis();
        ConcurrentSkipListSet jsl = new ConcurrentSkipListSet(new Comparator() {

            @Override
            public int compare(Object o1, Object o2) {
                return ((Comparable<String>) o1).compareTo((String) o2);
            }
        });
        for (int i = 0; i < _iterations; i++) {
            jsl.add(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
        }
        System.out.println("Java ConcurrentSkipListSet add(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jsl.size());

        random = new Random(seed);
        t = System.currentTimeMillis();
        TreeSet jtree = new TreeSet(new Comparator() {

            @Override
            public int compare(Object o1, Object o2) {
                return ((Comparable<String>) o1).compareTo((String) o2);
            }
        });
        for (int i = 0; i < _iterations; i++) {
            jtree.add(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
        }
        System.out.println("Java TreeSet add(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jtree.size());

        System.out.println("\nremove:");
        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            sls.slremove(filer, slsp, TestUtils.randomLowerCaseAlphaBytes(random, _keySize));
        }
        System.out.println("ByteSL remove(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + sls.slgetCount(filer, slsp));

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            jsl.remove(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
        }
        System.out.println("Java ConcurrentSkipListSet remove(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jsl.size());

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            jtree.remove(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
        }
        System.out.println("Java TreeSet remove(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jtree.size());

        System.out.println("\nadd and remove:");
        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            if (i % 2 == 0) {
                sls.slremove(filer, slsp, TestUtils.randomLowerCaseAlphaBytes(random, _keySize));
            } else {
                sls.sladd(filer, slsp, TestUtils.randomLowerCaseAlphaBytes(random, _keySize), FilerIO.intBytes(i));
            }
        }
        System.out.println("ByteSL add and remove (" + _maxSize + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + sls.slgetCount(filer, slsp));

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _iterations; i++) {
            if (i % 2 == 0) {
                jsl.remove(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
            } else {
                jsl.add(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
            }
        }
        System.out.println("Java ConcurrentSkipListSet add and remove(" + _iterations + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jsl.size());

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            if (i % 2 == 0) {
                jtree.remove(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
            } else {
                jtree.add(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
            }
        }
        System.out.println("Java TreeSet  add and remove (" + _maxSize + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jtree.size());

        System.out.println("\ncontains:");
        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            pset.contains(filer, slsp.chunk, TestUtils.randomLowerCaseAlphaBytes(random, _keySize));
        }
        System.out.println("ByteSL contains (" + _maxSize + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + sls.slgetCount(filer, slsp));

        random = new Random(seed);
        t = System.currentTimeMillis();
        for (int i = 0; i < _maxSize; i++) {
            jsl.contains(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
        }
        System.out.println("Java ConcurrentSkipListSet  contains (" + _maxSize + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jsl.size());

        System.out.println("\ncontains:");
        random = new Random(seed);
        for (int i = 0; i < _maxSize; i++) {
            jtree.contains(new String(TestUtils.randomLowerCaseAlphaBytes(random, _keySize)));
        }
        System.out.println("Java TreeSet  contains (" + _maxSize + ") took " + (System.currentTimeMillis() - t)
            + " Size:" + jtree.size());

        sls.sltoSysOut(filer, slsp, null);

        return true;

    }

    /**
     * @param provider
     */
    public static void chart(ByteBufferFactory provider) throws IOException {
        int ksize = 16;
        int payloadSize = 4;
        int maxSize = 1_000_000;

        int step = 10_000;
        System.out.println("mode,iterations,duration,size,mb");
        SkipListSet sls = new SkipListSet();

        for (int i = step; i < maxSize; i += step) {
            SkipListSetPage set = testSet(sls, null, 112_233, i, ksize, payloadSize, i, 0, true, provider);
            stats(ksize, payloadSize, i, provider);
            System.out.println();
        }
        for (int i = step; i < maxSize; i += step) {
            SkipListSetPage set = testSet(sls, null, 112_233, i, ksize, payloadSize, i, 0, false, provider);
            testSet(sls, set, 112_233, i, ksize, payloadSize, i, 1, true, provider);
            stats(ksize, payloadSize, i, provider);
            System.out.println();
        }
        for (int i = step; i < maxSize; i += step) {
            SkipListSetPage set = testSet(sls, null, 112_233, i, ksize, payloadSize, i, 2, true, provider);
            stats(ksize, payloadSize, i, provider);
            System.out.println();
        }
        for (int i = step; i < maxSize; i += step) {
            SkipListSetPage set = testSet(sls, null, 112_233, i, ksize, payloadSize, i, 2, false, provider);
            testSet(sls, set, 112_233, i, ksize, payloadSize, i, 3, true, provider);
            stats(ksize, payloadSize, i, provider);
            System.out.println();
        }
    }

    private static void stats(final int keySize, final int payloadSize, final int _maxSize, ByteBufferFactory provider)
        throws IOException {
        final MapStore pset = MapStore.INSTANCE;

        int filerSize = pset.computeFilerSize(_maxSize, keySize, false, payloadSize, false);
        ByteBufferBackedFiler filer = new ByteBufferBackedFiler(provider.allocate("booya".getBytes(), filerSize));

        byte[] headKey = new byte[keySize];
        Arrays.fill(headKey, Byte.MIN_VALUE);
        SkipListSet sls = new SkipListSet();
        SkipListSetPage slsp = sls.create(_maxSize, headKey, keySize, false, payloadSize, false, new SkipListComparator() {

            @Override
            public int compare(Filer a, int astart, Filer b, int bstart, int length) throws IOException {
                for (int i = 0; i < length; i++) {
                    byte av = pset.read(a, astart + i);
                    byte bv = pset.read(b, bstart + i);

                    if (av == bv) {
                        continue;
                    }
                    if (av < bv) {
                        return -1;
                    }
                    for (int j = i; j < length; j++) {
                        if (av < bv) {
                            return -1;
                        }
                    }
                    return 1;
                }
                return 0;
            }

            @Override
            public long range(byte[] a, byte[] b) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        }, filer);

    }

    private static SkipListSetPage testSet(SkipListSet sls, SkipListSetPage set, long seed, int _iterations, int keySize, int payloadSize, int _maxSize,
        int mode, boolean _out, ByteBufferFactory provider) throws IOException {

        //TODO
        /*
         if (set == null) {
         byte[] headKey = new byte[keySize];
         Arrays.fill(headKey, Byte.MIN_VALUE);
         final MapStore pset = MapStore.INSTANCE;
         set = sls.create(_maxSize, headKey, keySize, false, payloadSize, false, new SkipListComparator() {

         @Override
         public int compare(Filer a, int astart, Filer b, int bstart, int length) throws IOException {
         for (int i = 0; i < length; i++) {
         byte av = pset.read(a, astart + i);
         byte bv = pset.read(b, bstart + i);

         if (av == bv) {
         continue;
         }
         if (av < bv) {
         return -1;
         }
         for (int j = i; j < length; j++) {
         if (av < bv) {
         return -1;
         }
         }
         return 1;
         }
         return 0;
         }

         @Override
         public long range(byte[] a, byte[] b) {
         throw new UnsupportedOperationException("Not supported yet.");
         }
         }, filer);
         }

         System.out.println("\ncontains:");
         Random random = new Random(seed);
         long t = System.currentTimeMillis();
         if (mode == 0) {
         for (int i = 0; i < _iterations; i++) {
         sls.sladd(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize), TestUtils.randomLowerCaseAlphaBytes(random, payloadSize));
         }
         if (_out) {
         System.out.print("add," + _iterations + "," + (System.currentTimeMillis() - t));
         }
         }
         if (mode == 1) {
         for (int i = 0; i < _iterations; i++) {
         sls.slremove(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize));
         }
         if (_out) {
         System.out.print("remove," + _iterations + "," + (System.currentTimeMillis() - t));
         }
         }
         if (mode == 2) {
         for (int i = 0; i < _iterations; i++) {
         if (i % 2 == 0) {
         sls.slremove(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize));
         } else {
         sls.sladd(set, TestUtils.randomLowerCaseAlphaBytes(random, keySize), TestUtils.randomLowerCaseAlphaBytes(random, payloadSize));
         }
         }
         if (_out) {
         System.out.print("add/remove," + _iterations + "," + (System.currentTimeMillis() - t));
         }
         }
         if (mode == 3) {
         for (int i = 0; i < _iterations; i++) {
         pset.contains(set.chunk, TestUtils.randomLowerCaseAlphaBytes(random, keySize));
         }
         if (_out) {
         System.out.print("contains," + _iterations + "," + (System.currentTimeMillis() - t));
         }
         }
         */
        return set;
    }
    // have initial impl of a skip list backed by a byte[].. looks like ~1.12mb to store 10,000 (UIDS + a long)
    // looks like ~13.08mb to store 100,000 (UIDS + a long)
    // looks like ~146.8mb to store 1,000,000 (UIDS + a long)
    // one level deep
}
