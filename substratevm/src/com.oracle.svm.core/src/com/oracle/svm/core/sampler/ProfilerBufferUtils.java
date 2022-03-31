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

import org.graalvm.word.WordFactory;

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.locks.VMMutex;
import com.oracle.svm.core.util.VMError;

import jdk.jfr.internal.Options;

public class ProfilerBufferUtils {

    private static final long THREAD_BUFFER_SIZE = Options.getThreadBufferSize();

    private static final VMMutex mutex = new VMMutex("profilerBufferUtils");

    /**
     * The total number of profiler buffer in the system.
     */
    private static long bufferCount;

    /**
     * Push a profiler buffer into the global linked list.
     */
    @Uninterruptible(reason = "Locking without transition requires that the whole critical section is uninterruptible.", mayBeInlined = true)
    public static void allocateBuffers() {
        mutex.lockNoTransition();
        try {
            long diff = diff();
            for (int i = 0; i < diff; i++) {
                allocateAndPush();
            }
        } finally {
            mutex.unlock();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void allocateAndPush() {
        VMError.guarantee(bufferCount >= 0);
        ProfilerBuffer buffer = ProfilerBufferAccess.allocate(WordFactory.unsigned(THREAD_BUFFER_SIZE));
        if (buffer.isNull()) {
            return;
        }
        SubstrateSigprofHandler.availableBuffers().pushBuffer(buffer);
        bufferCount++;
    }

    /**
     * Free all buffers that are not necessary.
     */
    @Uninterruptible(reason = "Locking without transition requires that the whole critical section is uninterruptible.", mayBeInlined = true)
    public static void freeBuffers(boolean alreadyFreed) {
        mutex.lockNoTransition();
        try {
            if (alreadyFreed) {
                VMError.guarantee(bufferCount > 0);
                bufferCount--;
            }
            long diff = diff();
            if (diff < 0) {
                diff = -diff;
                for (int i = 0; i < diff; i++) {
                    pullAndFree();
                }
            }
        } finally {
            mutex.unlock();
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void pullAndFree() {
        VMError.guarantee(bufferCount > 0);
        ProfilerBuffer buffer = SubstrateSigprofHandler.availableBuffers().popBuffer();
        ProfilerBufferAccess.free(buffer);
        bufferCount--;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long diff() {
        double diffD = SubstrateSigprofHandler.getSubstrateThreadMXBean().getThreadCount() * 1.5 - bufferCount;
        long diffL = (long) diffD;
        return diffL + (diffL != diffD ? 1 : 0);
    }
}
