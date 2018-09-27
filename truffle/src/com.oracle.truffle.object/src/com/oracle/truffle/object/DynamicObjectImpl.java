/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.object;

import java.util.Iterator;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.LocationFactory;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;

/** @since 0.17 or earlier */
public abstract class DynamicObjectImpl extends DynamicObject implements Cloneable {
    private ShapeImpl shape;

    /** @since 0.17 or earlier */
    public static final DebugCounter reshapeCount = DebugCounter.create("Reshape count");

    /** @since 0.17 or earlier */
    protected DynamicObjectImpl(Shape shape) {
        assert shape instanceof ShapeImpl;
        initialize(shape);
        setShape(shape);

        if (ObjectStorageOptions.Profile) {
            Debug.trackObject(this);
        }
    }

    /** @since 0.17 or earlier */
    @SuppressWarnings("deprecation")
    public Object getTypeIdentifier() {
        return getShape();
    }

    /** @since 0.17 or earlier */
    @Override
    public ShapeImpl getShape() {
        return shape;
    }

    /** @since 0.17 or earlier */
    protected void setShape(Shape shape) {
        assert shape.getLayout().getType().isInstance(this);
        this.shape = (ShapeImpl) shape;
    }

    /** @since 0.17 or earlier */
    protected abstract void initialize(Shape initialShape);

    /** @since 0.17 or earlier */
    public final void setShapeAndResize(Shape newShape) {
        setShapeAndResize(getShape(), newShape);
    }

    /** @since 0.17 or earlier */
    @Override
    public final void setShapeAndResize(Shape oldShape, Shape newShape) {
        assert getShape() == oldShape : "wrong old shape";
        assert !oldShape.isShared();
        if (oldShape != newShape) {
            resizeStore(oldShape, newShape);
            setShape(newShape);

            assert checkExtensionArrayInvariants(newShape);
        }
    }

    /**
     * Set shape to an immediate child of the current shape, optionally growing the extension array.
     * Typically this would add a single property. Cannot shrink or grow more than one property at a
     * time.
     *
     * @see #setShapeAndResize(Shape, Shape)
     * @since 0.17 or earlier
     */
    @Override
    public final void setShapeAndGrow(Shape oldShape, Shape newShape) {
        assert getShape() == oldShape : "wrong old shape";
        if (oldShape != newShape) {
            assert checkSetShape(oldShape, newShape);

            growStore(oldShape, newShape);
            setShape(newShape);

            assert checkExtensionArrayInvariants(newShape);
        }
    }

    /**
     * Simpler version of {@link #resizeStore} when the object is only increasing in size.
     */
    private void growStore(Shape oldShape, Shape newShape) {
        growObjectStore(oldShape, newShape);
        if (((ShapeImpl) newShape).hasPrimitiveArray) {
            growPrimitiveStore(oldShape, newShape);
        }
    }

    /** @since 0.17 or earlier */
    protected abstract void growObjectStore(Shape oldShape, Shape newShape);

    /** @since 0.17 or earlier */
    protected abstract void growPrimitiveStore(Shape oldShape, Shape newShape);

    private void resizeStore(Shape oldShape, Shape newShape) {
        resizeObjectStore(oldShape, newShape);
        if (((ShapeImpl) newShape).hasPrimitiveArray) {
            resizePrimitiveStore(oldShape, newShape);
        }
    }

    /** @since 0.17 or earlier */
    protected abstract void resizePrimitiveStore(Shape oldShape, Shape newShape);

    /** @since 0.17 or earlier */
    protected abstract void resizeObjectStore(Shape oldShape, Shape newShape);

    /**
     * Check whether fast transition is valid.
     *
     * @see #setShapeAndGrow
     */
    private boolean checkSetShape(Shape oldShape, Shape newShape) {
        Shape currentShape = getShape();
        assert oldShape != newShape : "Wrong old shape assumption?";
        assert newShape != currentShape : "Redundant shape change? shape=" + currentShape;
        return true;
    }

    /**
     * Check whether the extension arrays are in accordance with the description in the shape.
     *
     * @since 0.17 or earlier
     */
    protected abstract boolean checkExtensionArrayInvariants(Shape newShape);

    /** @since 0.17 or earlier */
    @Override
    protected final DynamicObject clone() {
        try {
            return (DynamicObject) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException();
        }
    }

    /** @since 0.17 or earlier */
    protected abstract DynamicObject cloneWithShape(Shape currentShape);

    /** @since 0.17 or earlier */
    protected abstract void reshape(ShapeImpl newShape);

    /**
     * @param ancestor common ancestor shape between from and to object shapes
     * @since 0.17 or earlier
     */
    public final void copyProperties(DynamicObject fromObject, Shape ancestor) {
        copyProperties(fromObject);
    }

    private void copyProperties(DynamicObject fromObject) {
        ShapeImpl fromShape = (ShapeImpl) fromObject.getShape();
        ShapeImpl toShape = getShape();
        assert toShape.isRelated(fromShape);
        assert toShape.isValid();
        assert !fromShape.isShared();
        PropertyMap fromMap = fromShape.getPropertyMap();
        for (Iterator<Property> toMapIt = toShape.getPropertyMap().reverseOrderedValueIterator(); toMapIt.hasNext();) {
            Property toProperty = toMapIt.next();
            Property fromProperty = fromMap.get(toProperty.getKey());

            // copy only if property has a location and it's not the same as the source location
            if (!toProperty.getLocation().isValue() && !toProperty.getLocation().equals(fromProperty.getLocation())) {
                toProperty.setInternal(this, fromProperty.get(fromObject, false));
                assert toShape.isValid();
            }
        }
    }

    /** @since 0.17 or earlier */
    @TruffleBoundary
    public boolean changeFlags(Object key, int newFlags) {
        Shape oldShape = getShape();
        Property existing = oldShape.getProperty(key);
        if (existing != null) {
            if (existing.getFlags() != newFlags) {
                Property newProperty = existing.copyWithFlags(newFlags);
                Shape newShape = oldShape.replaceProperty(existing, newProperty);
                this.setShape(newShape);
            }
            return true;
        } else {
            return false;
        }
    }

    /** @since 0.17 or earlier */
    public String debugDump(int level) {
        return debugDump(0, level);
    }

    /** @since 0.17 or earlier */
    public String debugDump(int level, int levelStop) {
        return Debug.dumpObject(this, level, levelStop);
    }

    /** @since 0.17 or earlier */
    @Override
    public String toString() {
        return getShape().getObjectType().toString(this);
    }

    /** @since 0.17 or earlier */
    @Override
    public boolean equals(Object obj) {
        return getShape().getObjectType().equals(this, obj);
    }

    /** @since 0.17 or earlier */
    @Override
    public int hashCode() {
        return getShape().getObjectType().hashCode(this);
    }

    /** @since 0.17 or earlier */
    @Override
    @TruffleBoundary
    public Object get(Object key, Object defaultValue) {
        Property existing = getShape().getProperty(key);
        if (existing != null) {
            return existing.get(this, false);
        } else {
            return defaultValue;
        }
    }

    /** @since 0.17 or earlier */
    @Override
    @TruffleBoundary
    public boolean set(Object key, Object value) {
        Property existing = getShape().getProperty(key);
        if (existing != null) {
            existing.setGeneric(this, value, null);
            return true;
        } else {
            return false;
        }
    }

    /** @since 0.17 or earlier */
    @Override
    @TruffleBoundary
    public void define(Object key, Object value, int flags) {
        define(key, value, flags, getShape().getLayout().getStrategy().getDefaultLocationFactory());
    }

    /** @since 0.17 or earlier */
    @Override
    @TruffleBoundary
    public void define(Object key, Object value, int flags, LocationFactory locationFactory) {
        ShapeImpl oldShape = getShape();
        oldShape.getLayout().getStrategy().objectDefineProperty(this, key, value, flags, locationFactory, oldShape);
    }

    /** @since 0.17 or earlier */
    @Override
    @TruffleBoundary
    public boolean delete(Object key) {
        ShapeImpl oldShape = getShape();
        Property existing = oldShape.getProperty(key);
        if (existing != null) {
            oldShape.getLayout().getStrategy().objectRemoveProperty(this, existing, oldShape);
            return true;
        } else {
            return false;
        }
    }

    /** @since 0.17 or earlier */
    @Override
    public int size() {
        return getShape().getPropertyCount();
    }

    /** @since 0.17 or earlier */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /** @since 0.17 or earlier */
    @Override
    public final boolean updateShape() {
        return getShape().getLayout().getStrategy().updateShape(this);
    }

    /** @since 0.17 or earlier */
    @Override
    public final DynamicObject copy(Shape currentShape) {
        return cloneWithShape(currentShape);
    }

    /** @since 0.17 or earlier */
    @Override
    public ForeignAccess getForeignAccess() {
        return getShape().getForeignAccessFactory(this);
    }
}