/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.builtins.objects.cext;

import java.util.Objects;

import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.ValueProfile;

public class PythonAbstractNativeObject extends PythonAbstractObject implements PythonNativeObject, PythonNativeClass {

    public final TruffleObject object;

    public PythonAbstractNativeObject(TruffleObject object) {
        this.object = object;
    }

    public int compareTo(Object o) {
        return 0;
    }

    public Shape getInstanceShape() {
        CompilerDirectives.transferToInterpreter();
        throw new UnsupportedOperationException("native class does not have a shape");
    }

    public void lookupChanged() {
        // TODO invalidate cached native MRO
        CompilerDirectives.transferToInterpreter();
        throw new UnsupportedOperationException("not yet implemented");
    }

    public TruffleObject getPtr() {
        return object;
    }

    @Override
    public int hashCode() {
        CompilerAsserts.neverPartOfCompilation();
        // this is important for the default '__hash__' implementation
        return Objects.hashCode(object);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PythonAbstractNativeObject other = (PythonAbstractNativeObject) obj;
        return Objects.equals(object, other.object);
    }

    public boolean equalsProfiled(Object obj, ValueProfile profile) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PythonAbstractNativeObject other = (PythonAbstractNativeObject) obj;
        return Objects.equals(profile.profile(object), profile.profile(other.object));
    }

    @Override
    public String toString() {
        CompilerAsserts.neverPartOfCompilation();
        return String.format("PythonAbstractNativeObject(%s)", object);
    }
}