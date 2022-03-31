/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.core.sampler;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.jfr.SubstrateJVM;
import com.oracle.svm.core.thread.ThreadListener;
import com.oracle.svm.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.threadlocal.FastThreadLocalLong;
import com.oracle.svm.core.threadlocal.FastThreadLocalWord;
import com.oracle.svm.core.util.VMError;

public class ProfilerThreadLocal implements ThreadListener {

    private static final FastThreadLocalWord<ProfilerBuffer> localBuffer = FastThreadLocalFactory.createWord("ProfilerThreadLocal.localBuffer");
    private static final FastThreadLocalLong missedSamples = FastThreadLocalFactory.createLong("ProfilerThreadLocal.missedSamples");
    /**
     * The data that we are using during the stack walk, allocated on the stack.
     */
    private static final FastThreadLocalWord<ProfilerSampleWriterData> writerData = FastThreadLocalFactory.createWord("ProfilerThreadLocal.writerData");

    @Override
    @Uninterruptible(reason = "Only uninterruptible code may be executed before Thread.run.")
    public void beforeThreadRun(IsolateThread isolateThread, Thread javaThread) {
        if (SubstrateSigprofHandler.isProfilingEnabled()) {
            initialize(isolateThread);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void initialize(IsolateThread isolateThread) {
        /* Allocate new buffer(s). */
        ProfilerBufferUtils.allocateBuffers();

        /*
         * Save isolate thread in thread-local area.
         * 
         * Once this value is set, the signal handler may interrupt this thread at any time. So, it
         * is essential that this value is set at the very end of this method.
         */
        UnsignedWord key = ProfilerIsolateLocal.getKey();
        VMError.guarantee(key.aboveThan(0));
        SubstrateSigprofHandler.singleton().setThreadLocalKeyValue(key, isolateThread);
    }

    @Override
    @Uninterruptible(reason = "Only uninterruptible code may be executed after Thread.exit.")
    public void afterThreadExit(IsolateThread isolateThread, Thread javaThread) {
        if (SubstrateSigprofHandler.isProfilingEnabled()) {
            /*
             * Invalidate thread-local area.
             *
             * Once this value is set to null, the signal handler can't interrupt this thread
             * anymore. So, it is essential that this value is set at the very beginning of this
             * method i.e. before doing cleanup.
             */
            UnsignedWord key = ProfilerIsolateLocal.getKey();
            VMError.guarantee(key.aboveThan(0));
            SubstrateSigprofHandler.singleton().setThreadLocalKeyValue(key, WordFactory.nullPointer());

            /* Free the thread-local buffer. */
            ProfilerBuffer buffer = localBuffer.get(isolateThread);
            /* buffer will be null if no stack walk were performed. */
            if (buffer.isNonNull()) {
                if (ProfilerBufferAccess.isEmpty(buffer)) {
                    /* We can free it right away. */
                    ProfilerBufferAccess.free(buffer);
                } else {
                    /* Put it in the stack with other unprocessed buffers. */
                    buffer.setFreeable(true);
                    SubstrateSigprofHandler.fullBuffers().pushBuffer(buffer);
                }
                ProfilerBufferUtils.freeBuffers(true);
                localBuffer.set(isolateThread, WordFactory.nullPointer());
            } else {
                ProfilerBufferUtils.freeBuffers(false);
            }
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static ProfilerBuffer getThreadLocalBuffer() {
        return localBuffer.get();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void setThreadLocalBuffer(ProfilerBuffer buffer) {
        buffer.setOwner(SubstrateJVM.get().getThreadId());
        localBuffer.set(buffer);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void increaseMissedSamples() {
        missedSamples.set(getMissedSamples() + 1);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long getMissedSamples() {
        return missedSamples.get();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void setWriterData(ProfilerSampleWriterData data) {
        writerData.set(data);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static ProfilerSampleWriterData getWriterData() {
        return writerData.get();
    }
}
