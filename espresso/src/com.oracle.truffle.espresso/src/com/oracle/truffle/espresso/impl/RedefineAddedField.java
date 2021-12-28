/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.impl;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.staticobject.StaticShape;
import com.oracle.truffle.espresso.classfile.RuntimeConstantPool;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.StaticObject;
import org.graalvm.collections.EconomicMap;

public final class RedefineAddedField extends Field {

    private Field compatibleField;
    private StaticShape<ExtensionFieldObject.ExtensionFieldObjectFactory> extensionShape;

    private final EconomicMap<StaticObject, ExtensionFieldObject> extensionFieldsCache = EconomicMap.create();
    private final ExtensionFieldObject staticExtensionObject;

    public RedefineAddedField(ObjectKlass.KlassVersion holder, LinkedField linkedField, RuntimeConstantPool pool, boolean isDelegation) {
        super(holder, linkedField, pool);
        if (!isDelegation) {
            StaticShape.Builder shapeBuilder = StaticShape.newBuilder(getDeclaringKlass().getEspressoLanguage());
            shapeBuilder.property(linkedField, linkedField.getParserField().getPropertyType(), isFinalFlagSet());
            this.extensionShape = shapeBuilder.build(ExtensionFieldObject.FieldStorageObject.class, ExtensionFieldObject.ExtensionFieldObjectFactory.class);
        }
        if (isStatic()) {
            // create the extension field object eagerly for static fields
            staticExtensionObject = new ExtensionFieldObject();
        } else {
            staticExtensionObject = null;
        }
    }

    public static Field createDelegationField(Field field) {
        // update holder to latest klass version to ensure we
        // only re-resolve again when the class is redefined
        RedefineAddedField delegationField = new RedefineAddedField(field.getDeclaringKlass().getKlassVersion(), field.linkedField, field.pool, true);
        delegationField.setCompatibleField(field);
        return delegationField;
    }

    @Override
    public void setCompatibleField(Field field) {
        compatibleField = field;
    }

    @Override
    public boolean hasCompatibleField() {
        return compatibleField != null;
    }

    @Override
    public Field getCompatibleField() {
        return compatibleField;
    }

    StaticShape<ExtensionFieldObject.ExtensionFieldObjectFactory> getExtensionShape() {
        return extensionShape;
    }

    @TruffleBoundary
    private ExtensionFieldObject getExtensionObject(StaticObject instance) {
        if (isStatic()) {
            return staticExtensionObject;
        }

        ExtensionFieldObject extensionFieldObject = extensionFieldsCache.get(instance);
        if (extensionFieldObject == null) {
            synchronized (extensionFieldsCache) {
                extensionFieldObject = extensionFieldsCache.get(instance);
                if (extensionFieldObject == null) {
                    extensionFieldObject = new ExtensionFieldObject();
                    if (getDeclaringKlass() != instance.getKlass()) {
                        // we have to check if there's a field value
                        // in a subclass field that was removed in order
                        // to preserve the state of a pull-up field
                        checkPullUpField(instance, extensionFieldObject);
                    }
                    extensionFieldsCache.put(instance, extensionFieldObject);
                }
            }
        }
        return extensionFieldObject;
    }

    private void checkPullUpField(StaticObject instance, ExtensionFieldObject extensionFieldObject) {
        if (instance.getKlass() instanceof ObjectKlass) {
            ObjectKlass current = (ObjectKlass) instance.getKlass();
            while (current != getDeclaringKlass()) {
                Field removedField = current.getRemovedField(this);
                if (removedField != null) {
                    // OK, copy the state to the extension object
                    // @formatter:off
                    switch (getKind()) {
                        case Boolean: extensionFieldObject.setBoolean(this, removedField.linkedField.getBooleanVolatile(instance), true); break;
                        case Byte: extensionFieldObject.setByte(this, removedField.linkedField.getByteVolatile(instance), true); break;
                        case Short: extensionFieldObject.setShort(this, removedField.linkedField.getShortVolatile(instance), true); break;
                        case Char: extensionFieldObject.setChar(this, removedField.linkedField.getCharVolatile(instance), true); break;
                        case Int: extensionFieldObject.setInt(this, removedField.linkedField.getIntVolatile(instance), true); break;
                        case Float: extensionFieldObject.setFloat(this, removedField.linkedField.getFloatVolatile(instance), true); break;
                        case Long: extensionFieldObject.setLong(this, removedField.linkedField.getLongVolatile(instance), true); break;
                        case Double: extensionFieldObject.setDouble(this, removedField.linkedField.getDoubleVolatile(instance), true); break;
                        case Object: extensionFieldObject.setObject(this, removedField.linkedField.getObjectVolatile(instance), true); break;
                        default: throw EspressoError.shouldNotReachHere();
                    }
                    // @formatter:on
                    break;
                }
                current = current.getSuperKlass();
            }
        }
    }

    @Override
    protected StaticObject getObject(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getObject(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getObject(this, forceVolatile);
        }
    }

    @Override
    public void setObject(StaticObject obj, Object value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setObject(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setObject(this, value, forceVolatile);
        }
    }

    @Override
    public StaticObject getAndSetObject(StaticObject obj, StaticObject value) {
        if (hasCompatibleField()) {
            return getCompatibleField().getAndSetObject(obj, value);
        } else {
            return getExtensionObject(obj).getAndSetObject(this, value);
        }
    }

    @Override
    public boolean compareAndSwapObject(StaticObject obj, Object before, Object after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapObject(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapObject(this, before, after);
        }
    }

    @Override
    public StaticObject compareAndExchangeObject(StaticObject obj, Object before, Object after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeObject(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeObject(this, before, after);
        }
    }

    @Override
    public boolean getBoolean(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getBoolean(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getBoolean(this, forceVolatile);
        }
    }

    @Override
    public void setBoolean(StaticObject obj, boolean value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setBoolean(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setBoolean(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapBoolean(StaticObject obj, boolean before, boolean after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapBoolean(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapBoolean(this, before, after);
        }
    }

    @Override
    public boolean compareAndExchangeBoolean(StaticObject obj, boolean before, boolean after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeBoolean(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeBoolean(this, before, after);
        }
    }

    @Override
    public byte getByte(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getByte(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getByte(this, forceVolatile);
        }
    }

    @Override
    public void setByte(StaticObject obj, byte value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setByte(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setByte(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapByte(StaticObject obj, byte before, byte after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapByte(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapByte(this, before, after);
        }
    }

    @Override
    public byte compareAndExchangeByte(StaticObject obj, byte before, byte after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeByte(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeByte(this, before, after);
        }
    }

    @Override
    public char getChar(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getChar(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getChar(this, forceVolatile);
        }
    }

    @Override
    public void setChar(StaticObject obj, char value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setChar(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setChar(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapChar(StaticObject obj, char before, char after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapChar(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapChar(this, before, after);
        }
    }

    @Override
    public char compareAndExchangeChar(StaticObject obj, char before, char after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeChar(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeChar(this, before, after);
        }
    }

    @Override
    public double getDouble(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getDouble(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getDouble(this, forceVolatile);
        }
    }

    @Override
    public void setDouble(StaticObject obj, double value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setDouble(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setDouble(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapDouble(StaticObject obj, double before, double after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapDouble(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapDouble(this, before, after);
        }
    }

    @Override
    public double compareAndExchangeDouble(StaticObject obj, double before, double after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeDouble(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeDouble(this, before, after);
        }
    }

    @Override
    public float getFloat(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getFloat(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getFloat(this, forceVolatile);
        }
    }

    @Override
    public void setFloat(StaticObject obj, float value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setFloat(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setFloat(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapFloat(StaticObject obj, float before, float after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapFloat(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapFloat(this, before, after);
        }
    }

    @Override
    public float compareAndExchangeFloat(StaticObject obj, float before, float after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeFloat(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeFloat(this, before, after);
        }
    }

    @Override
    public int getInt(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getInt(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getInt(this, forceVolatile);
        }
    }

    @Override
    public void setInt(StaticObject obj, int value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setInt(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setInt(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapInt(StaticObject obj, int before, int after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapInt(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapInt(this, before, after);
        }
    }

    @Override
    public int compareAndExchangeInt(StaticObject obj, int before, int after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeInt(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeInt(this, before, after);
        }
    }

    @Override
    public long getLong(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getLong(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getLong(this, forceVolatile);
        }
    }

    @Override
    public void setLong(StaticObject obj, long value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setLong(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setLong(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapLong(StaticObject obj, long before, long after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapLong(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapLong(this, before, after);
        }
    }

    @Override
    public long compareAndExchangeLong(StaticObject obj, long before, long after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeLong(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeLong(this, before, after);
        }
    }

    @Override
    public short getShort(StaticObject obj, boolean forceVolatile) {
        if (hasCompatibleField()) {
            return getCompatibleField().getShort(obj, forceVolatile);
        } else {
            return getExtensionObject(obj).getShort(this, forceVolatile);
        }
    }

    @Override
    public void setShort(StaticObject obj, short value, boolean forceVolatile) {
        if (hasCompatibleField()) {
            getCompatibleField().setShort(obj, value, forceVolatile);
        } else {
            getExtensionObject(obj).setShort(this, value, forceVolatile);
        }
    }

    @Override
    public boolean compareAndSwapShort(StaticObject obj, short before, short after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndSwapShort(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndSwapShort(this, before, after);
        }
    }

    @Override
    public short compareAndExchangeShort(StaticObject obj, short before, short after) {
        if (hasCompatibleField()) {
            return getCompatibleField().compareAndExchangeShort(obj, before, after);
        } else {
            return getExtensionObject(obj).compareAndExchangeShort(this, before, after);
        }
    }
}
