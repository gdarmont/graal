/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.typestate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.flow.context.object.AnalysisObject;
import com.oracle.graal.pointsto.meta.AnalysisType;

public class ContextSensitiveSingleTypeState extends SingleTypeState {
    /** The objects of this type state. */
    protected final AnalysisObject[] objects;

    public ContextSensitiveSingleTypeState(PointsToAnalysis bb, boolean canBeNull, int properties, AnalysisType type, ArrayList<AnalysisObject> objects) {
        this(bb, canBeNull, properties, type, objects.toArray(AnalysisObject.EMPTY_ARRAY));
        assert objects.size() > 0 : "Single type state with no objects.";
    }

    /** Creates a new type state from incoming objects. */
    public ContextSensitiveSingleTypeState(PointsToAnalysis bb, boolean canBeNull, int properties, AnalysisType type, AnalysisObject... objects) {
        super(bb, canBeNull, properties, type);
        this.objects = objects;
        assert !bb.extendedAsserts() || checkObjects(bb);

        PointsToStats.registerTypeState(bb, this);
    }

    /** Create a type state with the same content and a reversed canBeNull value. */
    protected ContextSensitiveSingleTypeState(PointsToAnalysis bb, boolean canBeNull, ContextSensitiveSingleTypeState other) {
        super(bb, canBeNull, other);
        this.objects = other.objects;

        PointsToStats.registerTypeState(bb, this);
    }

    protected boolean checkObjects(BigBang bb) {
        assert bb.extendedAsserts();

        /* Check that the objects array are sorted by type. */
        for (int idx = 0; idx < objects.length - 1; idx++) {
            AnalysisObject o0 = objects[idx];
            AnalysisObject o1 = objects[idx + 1];

            assert o0 != null && o1 != null : "Object state must contain non null elements.";

            assert o0.type().equals(o1.type()) : "Single type state objects must have the same type.";
            /*
             * Check that the objects are sorted by ID. Since the objects should be unique (context
             * sensitive objects are merged when they have the same type during the union
             * operation), we use < for the test.
             */
            assert o0.getId() < o1.getId() : "Analysis objects must be sorted by ID.";
        }

        return true;
    }

    @Override
    protected Iterator<AnalysisType> typesIterator(BigBang bb) {
        return new Iterator<>() {

            boolean hasNext = true;

            @Override
            public boolean hasNext() {
                return hasNext;
            }

            @Override
            public AnalysisType next() {
                hasNext = false;
                return exactType();
            }
        };
    }

    @Override
    public boolean containsType(AnalysisType exactType) {
        return exactType().equals(exactType);
    }

    @Override
    public final int objectsCount() {
        return objects.length;
    }

    @Override
    public final AnalysisObject[] objects() {
        return objects;
    }

    @Override
    public Iterable<AnalysisObject> objectsIterable() {
        return Arrays.asList(objects);
    }

    @Override
    public AnalysisObject[] objectsArray(AnalysisType type) {
        return exactType().equals(type) ? objects : null;
    }

    @Override
    protected Iterator<AnalysisObject> objectsIterator(AnalysisType type) {
        return new Iterator<>() {
            private final boolean typesEqual = exactType().equals(type);
            private int idx = 0;

            @Override
            public boolean hasNext() {
                return typesEqual && idx < objects.length;
            }

            @Override
            public AnalysisObject next() {
                return objects[idx++];
            }
        };
    }

    @Override
    public TypeState forCanBeNull(PointsToAnalysis bb, boolean stateCanBeNull) {
        if (stateCanBeNull == this.canBeNull()) {
            return this;
        } else {
            return new ContextSensitiveSingleTypeState(bb, stateCanBeNull, this);
        }
    }

    /** Note that the objects of this type state have been merged. */
    @Override
    public void noteMerge(PointsToAnalysis bb) {
        assert bb.analysisPolicy().isMergingEnabled();

        if (!merged) {
            for (AnalysisObject obj : objects) {
                obj.noteMerge(bb);
            }
            merged = true;
        }
    }

    @Override
    public boolean isMerged() {
        return merged;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Arrays.hashCode(objects);
        result = 31 * result + (canBeNull ? 1 : 0);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ContextSensitiveSingleTypeState that = (ContextSensitiveSingleTypeState) o;
        return this.canBeNull == that.canBeNull && this.exactType().equals(that.exactType()) && Arrays.equals(this.objects, that.objects);
    }

    @Override
    public String toString() {
        return "1TypeMObject<" + (canBeNull ? "null," : "") + Arrays.toString(objects) + ">";
    }

}
