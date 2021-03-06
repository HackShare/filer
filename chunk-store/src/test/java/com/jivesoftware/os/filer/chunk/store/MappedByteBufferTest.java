package com.jivesoftware.os.filer.chunk.store;

import com.jivesoftware.os.filer.io.ByteBufferBackedFiler;
import com.jivesoftware.os.filer.io.FileBackedMemMappedByteBufferFactory;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.filer.io.api.StackBuffer;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 *
 */
public class MappedByteBufferTest {

    @Test(enabled = false, description = "Tests whether stale buffers are still accurate, and that maps to the same file don't use extra FDs")
    public void testRemappedBuffers() throws Exception {
        StackBuffer stackBuffer = new StackBuffer();
        File baseDir = Files.createTempDirectory("maps").toFile();
        FileBackedMemMappedByteBufferFactory factory = new FileBackedMemMappedByteBufferFactory("f", 0, baseDir);

        ByteBuffer buf1 = factory.allocate("test".getBytes(), 1_000);
        ByteBufferBackedFiler filer1 = new ByteBufferBackedFiler(buf1);
        assertEquals(filer1.length(), 1_001);

        filer1.seek(0);
        for (int i = 0; i < 10; i++) {
            FilerIO.writeLong(filer1, i, "i", stackBuffer);
        }

        filer1.seek(0);
        for (int i = 0; i < 10; i++) {
            assertEquals(FilerIO.readLong(filer1, "i", stackBuffer), i);
        }

        ByteBuffer buf2 = factory.allocate("test".getBytes(), 2_000);
        ByteBufferBackedFiler filer2 = new ByteBufferBackedFiler(buf2);
        assertEquals(filer2.length(), 2_001);

        filer2.seek(10 * 8);
        for (int i = 10; i < 20; i++) {
            FilerIO.writeLong(filer2, i, "i", stackBuffer);
        }

        filer1.seek(20 * 8);
        for (int i = 20; i < 30; i++) {
            FilerIO.writeLong(filer1, i, "i", stackBuffer);
        }

        filer1.seek(0);
        for (int i = 0; i < 30; i++) {
            assertEquals(FilerIO.readLong(filer1, "i", stackBuffer), i);
        }

        filer2.seek(0);
        for (int i = 0; i < 30; i++) {
            assertEquals(FilerIO.readLong(filer2, "i", stackBuffer), i);
        }

        for (int i = 0; i < 1_000_000; i++) {
            ByteBufferBackedFiler filerX = new ByteBufferBackedFiler(factory.allocate("test".getBytes(), 3_000));
            filerX.seek(0);
            FilerIO.writeLong(filerX, i, "i", stackBuffer);
            filerX.seek(0);
            assertEquals(FilerIO.readLong(filerX, "i", stackBuffer), i);
            Thread.sleep(10);
        }

    }
}
