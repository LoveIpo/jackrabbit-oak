/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.segment.file;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newLinkedHashMap;
import static com.google.common.collect.Sets.newTreeSet;
import static org.apache.jackrabbit.oak.segment.SegmentWriterBuilder.segmentWriterBuilder;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.jackrabbit.oak.segment.file.FileStoreBuilder.fileStoreBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.Monitor;
import com.google.common.util.concurrent.Monitor.Guard;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.plugins.memory.AbstractBlob;
import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentNodeBuilder;
import org.apache.jackrabbit.oak.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.segment.SegmentWriter;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileStoreIT {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File("target"));

    private File getFileStoreFolder() {
        return folder.getRoot();
    }

    @Test
    public void testRestartAndGCWithoutMM() throws Exception {
        testRestartAndGC(false);
    }

    @Test
    public void testRestartAndGCWithMM() throws Exception {
        testRestartAndGC(true);
    }

    public void testRestartAndGC(boolean memoryMapping) throws Exception {
        FileStore store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(memoryMapping).build();
        store.close();

        store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(memoryMapping).build();
        SegmentNodeState base = store.getHead();
        SegmentNodeBuilder builder = base.builder();
        byte[] data = new byte[10 * 1024 * 1024];
        new Random().nextBytes(data);
        Blob blob = builder.createBlob(new ByteArrayInputStream(data));
        builder.setProperty("foo", blob);
        store.getRevisions().setHead(base.getRecordId(), builder.getNodeState().getRecordId());
        store.flush();
        store.getRevisions().setHead(store.getRevisions().getHead(), base.getRecordId());
        store.close();

        store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(memoryMapping).build();
        store.gc();
        store.flush();
        store.close();

        store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(memoryMapping).build();
        store.close();
    }

    @Test
    public void testRecovery() throws Exception {
        FileStore store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(false).build();
        store.flush();

        RandomAccessFile data0 = new RandomAccessFile(new File(getFileStoreFolder(), "data00000a.tar"), "r");
        long pos0 = data0.length();

        SegmentNodeState base = store.getHead();
        SegmentNodeBuilder builder = base.builder();
        builder.setProperty("step", "a");
        store.getRevisions().setHead(base.getRecordId(), builder.getNodeState().getRecordId());
        store.flush();
        long pos1 = data0.length();
        data0.close();

        base = store.getHead();
        builder = base.builder();
        builder.setProperty("step", "b");
        store.getRevisions().setHead(base.getRecordId(), builder.getNodeState().getRecordId());
        store.close();

        store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(false).build();
        assertEquals("b", store.getHead().getString("step"));
        store.close();

        RandomAccessFile file = new RandomAccessFile(
                new File(getFileStoreFolder(), "data00000a.tar"), "rw");
        file.setLength(pos1);
        file.close();

        store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(false).build();
        assertEquals("a", store.getHead().getString("step"));
        store.close();

        file = new RandomAccessFile(
                new File(getFileStoreFolder(), "data00000a.tar"), "rw");
        file.setLength(pos0);
        file.close();

        store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(false).build();
        assertFalse(store.getHead().hasProperty("step"));
        store.close();
    }

    @Test
    public void testRearrangeOldData() throws IOException {
        new FileOutputStream(new File(getFileStoreFolder(), "data00000.tar")).close();
        new FileOutputStream(new File(getFileStoreFolder(), "data00010a.tar")).close();
        new FileOutputStream(new File(getFileStoreFolder(), "data00030.tar")).close();
        new FileOutputStream(new File(getFileStoreFolder(), "bulk00002.tar")).close();
        new FileOutputStream(new File(getFileStoreFolder(), "bulk00005a.tar")).close();

        Map<Integer, ?> files = FileStore.collectFiles(getFileStoreFolder());
        assertEquals(
                newArrayList(0, 1, 31, 32, 33),
                newArrayList(newTreeSet(files.keySet())));

        assertTrue(new File(getFileStoreFolder(), "data00000a.tar").isFile());
        assertTrue(new File(getFileStoreFolder(), "data00001a.tar").isFile());
        assertTrue(new File(getFileStoreFolder(), "data00031a.tar").isFile());
        assertTrue(new File(getFileStoreFolder(), "data00032a.tar").isFile());
        assertTrue(new File(getFileStoreFolder(), "data00033a.tar").isFile());

        files = FileStore.collectFiles(getFileStoreFolder());
        assertEquals(
                newArrayList(0, 1, 31, 32, 33),
                newArrayList(newTreeSet(files.keySet())));
    }

    @Test  // See OAK-2049
    public void segmentOverflow() throws Exception {
        for (int n = 1; n < 255; n++) {  // 255 = ListRecord.LEVEL_SIZE
            FileStore store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(false).build();
            SegmentWriter writer = store.getWriter();
            // writer.length == 32  (from the root node)

            // adding 15 strings with 16516 bytes each
            for (int k = 0; k < 15; k++) {
                // 16516 = (Segment.MEDIUM_LIMIT - 1 + 2 + 3)
                // 1 byte per char, 2 byte to store the length and 3 bytes for the
                // alignment to the integer boundary
                writer.writeString(Strings.repeat("abcdefghijklmno".substring(k, k + 1),
                        Segment.MEDIUM_LIMIT - 1));
            }

            // adding 14280 bytes. 1 byte per char, and 2 bytes to store the length
            RecordId x = writer.writeString(Strings.repeat("x", 14278));
            // writer.length == 262052

            // Adding 765 bytes (255 recordIds)
            // This should cause the current segment to flush
            List<RecordId> list = Collections.nCopies(n, x);
            writer.writeList(list);

            writer.flush();

            // Don't close the store in a finally clause as if a failure happens
            // this will also fail an cover up the earlier exception
            store.close();
        }
    }

    @Test
    public void nonBlockingROStore() throws Exception {
        FileStore store = fileStoreBuilder(getFileStoreFolder()).withMaxFileSize(1).withMemoryMapping(false).build();
        store.flush(); // first 1kB
        SegmentNodeState base = store.getHead();
        SegmentNodeBuilder builder = base.builder();
        builder.setProperty("step", "a");
        store.getRevisions().setHead(base.getRecordId(), builder.getNodeState().getRecordId());
        store.flush(); // second 1kB

        ReadOnlyFileStore ro = null;
        try {
            ro = fileStoreBuilder(getFileStoreFolder()).buildReadOnly();
            assertEquals(store.getRevisions().getHead(), ro.getRevisions().getHead());
        } finally {
            if (ro != null) {
                ro.close();
            }
            store.close();
        }
    }

    @Test
    public void setRevisionTest() throws Exception {
        try (FileStore store = fileStoreBuilder(getFileStoreFolder()).build()) {
            RecordId id1 = store.getRevisions().getHead();
            SegmentNodeState base = store.getHead();
            SegmentNodeBuilder builder = base.builder();
            builder.setProperty("step", "a");
            store.getRevisions().setHead(base.getRecordId(), builder.getNodeState().getRecordId());
            RecordId id2 = store.getRevisions().getHead();
            store.flush();

            try (ReadOnlyFileStore roStore = fileStoreBuilder(getFileStoreFolder()).buildReadOnly()) {
                assertEquals(id2, roStore.getRevisions().getHead());

                roStore.setRevision(id1.toString());
                assertEquals(id1, roStore.getRevisions().getHead());

                roStore.setRevision(id2.toString());
                assertEquals(id2, roStore.getRevisions().getHead());
            }
        }
    }

    @Test
    public void snfeAfterOnRC()
    throws IOException, InvalidFileStoreVersionException, InterruptedException {
        Map<String, String> roots = newLinkedHashMap();
        try (FileStore rwStore = fileStoreBuilder(getFileStoreFolder()).build()) {

            // Block scheduled journal updates
            CountDownLatch blockJournalUpdates = new CountDownLatch(1);

            // Ensure we have a non empty journal
            rwStore.flush();

            // Add a revisions
            if (!roots.containsKey("q")) {
                roots.put(addNode(rwStore, "g"), "g");
            }

            // Simulate compaction by writing a new head state of the next generation
            SegmentNodeState base = rwStore.getHead();
            int gcGeneration = base.getRecordId().getSegmentId().getGcGeneration();
            SegmentWriter nextGenerationWriter = segmentWriterBuilder("c")
                    .withGeneration(gcGeneration + 1)
                    .build(rwStore);
            RecordId headId = nextGenerationWriter.writeNode(EmptyNodeState.EMPTY_NODE).getRecordId();
            rwStore.getRevisions().setHead(base.getRecordId(), headId);

            // Add another revisions
            if (!roots.containsKey("g")) {
                roots.put(addNode(rwStore, "g"), "g");
            }
            blockJournalUpdates.countDown();
        }

        // Open the store again in read only mode and check all revisions.
        // This simulates accessing the store after an unclean shutdown.
        try (ReadOnlyFileStore roStore = fileStoreBuilder(getFileStoreFolder()).buildReadOnly()) {
            for (Entry<String, String> revision : roots.entrySet()) {
                roStore.setRevision(revision.getKey());
                checkNode(roStore.getHead());
            }
        }
    }

    private static String addNode(FileStore store, String name) throws InterruptedException {
        SegmentNodeState base = store.getHead();
        SegmentNodeBuilder builder = base.builder();
        builder.setChildNode(name);
        store.getRevisions().setHead(base.getRecordId(), builder.getNodeState().getRecordId());
        return store.getRevisions().getHead().toString();
    }

    private static void checkNode(NodeState node) {
        for (ChildNodeEntry cne : node.getChildNodeEntries()) {
            checkNode(cne.getNodeState());
        }
    }

    @Ignore("OAK-7867")
    @Test
    public void blockingBlob() throws Exception {

        /* A blob that blocks on read until unblocked */
        class BlockingBlob extends AbstractBlob {
            private final AtomicBoolean blocking = new AtomicBoolean(true);
            private final Monitor readMonitor = new Monitor();
            private boolean reading = false;

            public boolean waitForRead(int time, TimeUnit unit) throws InterruptedException {
                readMonitor.enter();
                try {
                    return readMonitor.waitFor(new Guard(readMonitor) {
                        @Override
                        public boolean isSatisfied() {
                            return reading;
                        }
                    }, time, unit);
                } finally {
                    readMonitor.leave();
                }
            }

            public void unblock() {
                blocking.set(false);
            }

            @Nonnull
            @Override
            public InputStream getNewStream() {
                return new InputStream() {

                    @Override
                    public int read() throws IOException {
                        return readOrEnd();
                    }

                    @Override
                    public int read(@Nonnull byte[] b, int off, int len) throws IOException {
                        return readOrEnd();
                    }

                    private int readOrEnd() {
                        if (blocking.get()) {
                            reading = true;
                            return 0;
                        } else {
                            return -1;
                        }
                    }
                };
            }

            @Override
            public long length() {
                return 1;
            }
        }

        ExecutorService updateExecutor = newSingleThreadExecutor();
        ExecutorService flushExecutor = newSingleThreadExecutor();
        try (FileStore store = fileStoreBuilder(getFileStoreFolder()).build()) {

            // A blob whose stream blocks on read
            final BlockingBlob blockingBlob = new BlockingBlob();

            // Use a background thread to add the blocking blob to a property
            updateExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    SegmentNodeState root = store.getHead();
                    SegmentNodeBuilder builder = root.builder();
                    builder.setProperty("blockingBlob", blockingBlob);
                    store.getRevisions().setHead(root.getRecordId(), builder.getNodeState().getRecordId());
                }
            });

            // Wait for reading on the blob to block
            assertTrue(blockingBlob.waitForRead(1, SECONDS));

            // In another background thread flush the file store
            Future<Void> flushed = flushExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    store.flush();
                    return null;
                }
            });

            // Flush should not get blocked by the blob blocked on reading
            try {
                flushed.get(10, SECONDS);
            } catch (TimeoutException e) {
                fail("Flush must not block");
            } finally {
                blockingBlob.unblock();
            }
        } finally {
            flushExecutor.shutdown();
            updateExecutor.shutdown();
        }
    }

}
