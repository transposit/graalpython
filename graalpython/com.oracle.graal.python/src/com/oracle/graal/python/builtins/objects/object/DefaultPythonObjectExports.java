/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.builtins.objects.object;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.type.LazyPythonClass;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(value = PythonObjectLibrary.class, receiverType = Object.class)
final class DefaultPythonObjectExports {
    @ExportMessage
    static boolean isSequence(Object receiver,
                    @CachedLibrary("receiver") InteropLibrary interopLib) {
        return interopLib.hasArrayElements(receiver);
    }

    @ExportMessage
    static boolean isMapping(Object receiver,
                    @CachedLibrary("receiver") InteropLibrary interopLib) {
        return interopLib.hasMembers(receiver);
    }

    @ExportMessage
    static boolean canBeIndex(Object receiver,
                    @CachedLibrary("receiver") InteropLibrary interopLib) {
        return interopLib.fitsInLong(receiver);
    }

    @ExportMessage
    static Object asIndex(Object receiver,
                    @Shared("raiseNode") @Cached PRaiseNode raise,
                    @CachedLibrary("receiver") InteropLibrary interopLib) {
        if (interopLib.fitsInLong(receiver)) {
            try {
                return interopLib.asLong(receiver);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        } else if (interopLib.isBoolean(receiver)) {
            try {
                return interopLib.asBoolean(receiver) ? 1 : 0;
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        } else {
            throw raise.raiseIntegerInterpretationError(receiver);
        }
    }

    @ExportMessage
    static int asSize(Object receiver, LazyPythonClass type,
                    @Shared("raiseNode") @Cached PRaiseNode raise,
                    @CachedLibrary(limit = "2") InteropLibrary interopLib) {
        Object index = asIndex(receiver, raise, interopLib);
        if (interopLib.fitsInInt(index)) {
            try {
                return interopLib.asInt(index);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        } else {
            throw raise.raiseNumberTooLarge(type, index);
        }
    }

    @ExportMessage
    static LazyPythonClass getLazyPythonClass(@SuppressWarnings("unused") Object value) {
        return PythonBuiltinClassType.ForeignObject;
    }

    @ExportMessage
    @TruffleBoundary
    static long hash(Object receiver) {
        return receiver.hashCode();
    }

    @ExportMessage
    static int length(Object receiver,
                    @Shared("raiseNode") @Cached PRaiseNode raise,
                    @CachedLibrary("receiver") InteropLibrary interopLib) {
        if (interopLib.hasArrayElements(receiver)) {
            long sz;
            try {
                sz = interopLib.getArraySize(receiver);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
            if (sz == (int) sz) {
                return (int) sz;
            } else {
                throw raise.raiseNumberTooLarge(PythonBuiltinClassType.OverflowError, sz);
            }
        } else {
            throw raise.raiseHasNoLength(receiver);
        }
    }

    @ExportMessage
    static class IsTrue {
        @Specialization(guards = "lib.isBoolean(receiver)")
        static boolean bool(Object receiver,
                        @CachedLibrary("receiver") InteropLibrary lib) {
            try {
                return lib.asBoolean(receiver);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }

        @Specialization(guards = "lib.fitsInLong(receiver)")
        static boolean integer(Object receiver,
                        @CachedLibrary("receiver") InteropLibrary lib) {
            try {
                return lib.asLong(receiver) != 0;
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }

        @Specialization(guards = "lib.fitsInDouble(receiver)")
        static boolean floatingPoint(Object receiver,
                        @CachedLibrary("receiver") InteropLibrary lib) {
            try {
                return lib.asDouble(receiver) != 0.0;
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }

        @Specialization(guards = "lib.hasArrayElements(receiver)")
        static boolean array(Object receiver,
                        @CachedLibrary("receiver") InteropLibrary lib) {
            try {
                return lib.getArraySize(receiver) > 0;
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }

        @Specialization(guards = {
                        "!lib.isBoolean(receiver)", "!lib.fitsInLong(receiver)",
                        "!lib.fitsInDouble(receiver)", "!lib.hasArrayElements(receiver)"
        })
        static boolean generic(Object receiver,
                        @CachedLibrary("receiver") InteropLibrary lib) {
            return !lib.isNull(receiver);
        }
    }
}
