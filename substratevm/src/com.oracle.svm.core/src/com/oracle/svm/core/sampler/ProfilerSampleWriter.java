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

import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.UnmanagedMemoryUtil;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.util.VMError;

public class ProfilerSampleWriter {

    private static final int END_MARKER_SIZE = Long.BYTES;
    private static final long END_MARKER = -1;

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean putLong(ProfilerSampleWriterData data, long value) {
        if (ensureSize(data, Long.BYTES)) {
            data.getCurrentPos().writeLong(0, value);
            increaseCurrentPos(data, WordFactory.unsigned(Long.BYTES));
            return true;
        } else {
            return false;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void commit(ProfilerSampleWriterData data) {
        ProfilerBuffer buffer = data.getProfilerBuffer();
        /*
         * put END_MARKER should not fail as accommodation takes end marker size in consideration.
         */
        VMError.guarantee(getAvailableSize(data).aboveOrEqual(END_MARKER_SIZE));
        data.getCurrentPos().writeLong(0, END_MARKER);
        increaseCurrentPos(data, WordFactory.unsigned(Long.BYTES));

        buffer.setPos(data.getCurrentPos());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean ensureSize(ProfilerSampleWriterData data, int requested) {
        assert requested > 0;
        int totalRequested = requested + END_MARKER_SIZE;
        if (getAvailableSize(data).belowThan(totalRequested)) {
            if (!accommodate(data, getUncommittedSize(data))) {
                return false;
            }
        }
        assert getAvailableSize(data).aboveOrEqual(totalRequested);
        return true;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean accommodate(ProfilerSampleWriterData data, UnsignedWord uncommitted) {
        if (ProfilerBufferAccess.isEmpty(data.getProfilerBuffer())) {
            /*
             * Sample is too big to fit into the size of one buffer i.e. we want to do
             * accommodations while nothing is committed into buffer.
             */
            ProfilerThreadLocal.increaseMissedSamples();
            return false;
        }

        /* Pop first free buffer from the pool. */
        ProfilerBuffer newBuffer = SubstrateSigprofHandler.availableBuffers().popBuffer();
        if (newBuffer.isNull()) {
            /* No available buffers on the pool. Fallback! */
            ProfilerThreadLocal.increaseMissedSamples();
            return false;
        }
        ProfilerThreadLocal.setThreadLocalBuffer(newBuffer);

        /* Copy the uncommitted content of old buffer into new one. */
        UnmanagedMemoryUtil.copy(data.getStartPos(), ProfilerBufferAccess.getDataStart(newBuffer), uncommitted);

        /* Put in the stack with other unprocessed buffers. */
        ProfilerBuffer oldBuffer = data.getProfilerBuffer();
        SubstrateSigprofHandler.fullBuffers().pushBuffer(oldBuffer);

        /* Reinitialize data structure. */
        data.setProfilerBuffer(newBuffer);
        hardReset(data);
        increaseCurrentPos(data, uncommitted);
        return true;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static UnsignedWord getAvailableSize(ProfilerSampleWriterData data) {
        return data.getEndPos().subtract(data.getCurrentPos());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static UnsignedWord getUncommittedSize(ProfilerSampleWriterData data) {
        return data.getCurrentPos().subtract(data.getStartPos());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void increaseCurrentPos(ProfilerSampleWriterData data, UnsignedWord delta) {
        data.setCurrentPos(data.getCurrentPos().add(delta));
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void hardReset(ProfilerSampleWriterData data) {
        ProfilerBuffer buffer = data.getProfilerBuffer();
        data.setStartPos(buffer.getPos());
        data.setCurrentPos(buffer.getPos());
        data.setEndPos(ProfilerBufferAccess.getDataEnd(buffer));
    }
}
