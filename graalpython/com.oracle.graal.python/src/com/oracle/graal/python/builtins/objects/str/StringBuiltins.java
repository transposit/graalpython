/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.builtins.objects.str;

import static com.oracle.graal.python.nodes.SpecialMethodNames.__ADD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__CONTAINS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__EQ__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETITEM__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__ITER__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__MOD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__NE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__RADD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__REPR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__RMUL__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__STR__;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.IndexError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.LookupError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.MemoryError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.UnicodeEncodeError;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.ValueError;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.common.HashingStorage;
import com.oracle.graal.python.builtins.objects.common.HashingStorageNodes.SetItemNode;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes.GetObjectArrayNode;
import com.oracle.graal.python.builtins.objects.common.SequenceNodesFactory.GetObjectArrayNodeGen;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.iterator.PStringIterator;
import com.oracle.graal.python.builtins.objects.list.ListBuiltins.ListReverseNode;
import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.builtins.objects.object.PythonObjectLibrary;
import com.oracle.graal.python.builtins.objects.slice.PSlice;
import com.oracle.graal.python.builtins.objects.slice.PSlice.SliceInfo;
import com.oracle.graal.python.builtins.objects.str.StringNodes.CastToJavaStringCheckedNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.JoinInternalNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.SpliceNode;
import com.oracle.graal.python.builtins.objects.str.StringNodes.StringLenNode;
import com.oracle.graal.python.builtins.objects.str.StringUtils.StripKind;
import com.oracle.graal.python.builtins.objects.tuple.PTuple;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.SpecialMethodNames;
import com.oracle.graal.python.nodes.attributes.LookupAttributeInMRONode;
import com.oracle.graal.python.nodes.builtins.ListNodes.AppendNode;
import com.oracle.graal.python.nodes.call.CallNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallBinaryNode;
import com.oracle.graal.python.nodes.classes.IsSubtypeNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonQuaternaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonTernaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.object.GetLazyClassNode;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.nodes.subscript.GetItemNode;
import com.oracle.graal.python.nodes.subscript.SliceLiteralNode.CastToSliceComponentNode;
import com.oracle.graal.python.nodes.truffle.PythonArithmeticTypes;
import com.oracle.graal.python.nodes.util.CastToJavaIntNode;
import com.oracle.graal.python.nodes.util.CastToJavaStringNode;
import com.oracle.graal.python.nodes.util.CastToJavaStringNodeGen;
import com.oracle.graal.python.runtime.ExecutionContext.IndirectCallContext;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.formatting.StringFormatter;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreFunctions(extendClasses = PythonBuiltinClassType.PString)
public final class StringBuiltins extends PythonBuiltins {

    private static final String INVALID_RECEIVER = "'%s' requires a 'str' object but received a '%p'";
    public static final String INVALID_FIRST_ARG = "%s first arg must be str or a tuple of str, not %p";
    public static final String MUST_BE_STR = "must be str, not %p";

    @Override
    protected List<com.oracle.truffle.api.dsl.NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return StringBuiltinsFactory.getFactories();
    }

    @Builtin(name = __STR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class StrNode extends PythonUnaryBuiltinNode {
        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return castToJavaStringNode.cast(self, INVALID_RECEIVER, __STR__, self);
        }
    }

    @Builtin(name = __REPR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class ReprNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return repr(castToJavaStringNode.cast(self, INVALID_RECEIVER, __STR__, self));
        }

        @TruffleBoundary
        static String repr(String self) {
            boolean hasSingleQuote = self.contains("'");
            boolean hasDoubleQuote = self.contains("\"");
            boolean useDoubleQuotes = hasSingleQuote && !hasDoubleQuote;

            StringBuilder str = new StringBuilder(self.length() + 2);
            str.append(useDoubleQuotes ? '"' : '\'');
            int offset = 0;
            while (offset < self.length()) {
                int codepoint = self.codePointAt(offset);
                switch (codepoint) {
                    case '\n':
                        str.append("\\n");
                        break;
                    case '\r':
                        str.append("\\r");
                        break;
                    case '\t':
                        str.append("\\t");
                        break;
                    case '\b':
                        str.append("\\b");
                        break;
                    case 7:
                        str.append("\\a");
                        break;
                    case '\f':
                        str.append("\\f");
                        break;
                    case 11:
                        str.append("\\v");
                        break;
                    case '\\':
                        str.append("\\\\");
                        break;
                    case '"':
                        if (useDoubleQuotes) {
                            str.append("\\\"");
                        } else {
                            str.append('\"');
                        }
                        break;
                    case '\'':
                        if (useDoubleQuotes) {
                            str.append('\'');
                        } else {
                            str.append("\\'");
                        }
                        break;
                    default:
                        if (codepoint < 32 || (codepoint >= 0x7f && codepoint <= 0xa0)) {
                            str.append("\\x").append(String.format("%02x", codepoint));
                        } else if (codepoint > 0xd7fc) { // determined by experimentation
                            if (codepoint < 0x10000) {
                                str.append("\\u").append(String.format("%04x", codepoint));
                            } else {
                                str.append("\\U").append(String.format("%08x", codepoint));
                            }
                        } else {
                            str.appendCodePoint(codepoint);
                        }
                        break;
                }
                offset += Character.charCount(codepoint);
            }
            str.append(useDoubleQuotes ? '"' : '\'');
            return str.toString();
        }
    }

    abstract static class BinaryStringOpNode extends PythonBinaryBuiltinNode {
        @Specialization
        boolean eq(String self, String other) {
            return operator(self, other);
        }

        @Specialization
        Object doGeneric(Object self, Object other,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringNode castOtherNode,
                        @Cached("createBinaryProfile()") ConditionProfile otherIsStringProfile) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, __EQ__, self);
            String otherStr = castOtherNode.execute(other);
            if (otherIsStringProfile.profile(otherStr != null)) {
                return operator(selfStr, otherStr);
            }
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        boolean operator(String self, String other) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("should not be reached");
        }
    }

    @Builtin(name = __CONTAINS__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class ContainsNode extends BinaryStringOpNode {
        @Override
        @TruffleBoundary
        boolean operator(String self, String other) {
            return self.contains(other);
        }
    }

    @Builtin(name = __EQ__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class EqNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return self.equals(other);
        }
    }

    @Builtin(name = __NE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class NeNode extends BinaryStringOpNode {
        @Override
        boolean operator(String self, String other) {
            return !self.equals(other);
        }
    }

    @Builtin(name = __LT__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LtNode extends BinaryStringOpNode {
        @Override
        @TruffleBoundary
        boolean operator(String self, String other) {
            return self.compareTo(other) < 0;
        }
    }

    @Builtin(name = __LE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LeNode extends BinaryStringOpNode {
        @Override
        @TruffleBoundary
        boolean operator(String self, String other) {
            return self.compareTo(other) <= 0;
        }
    }

    @Builtin(name = __GT__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GtNode extends BinaryStringOpNode {
        @Override
        @TruffleBoundary
        boolean operator(String self, String other) {
            return self.compareTo(other) > 0;
        }
    }

    @Builtin(name = __GE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GeNode extends BinaryStringOpNode {
        @Override
        @TruffleBoundary
        boolean operator(String self, String other) {
            return self.compareTo(other) >= 0;
        }
    }

    @Builtin(name = __ADD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class AddNode extends PythonBinaryBuiltinNode {

        protected final ConditionProfile leftProfile1 = ConditionProfile.createBinaryProfile();
        protected final ConditionProfile leftProfile2 = ConditionProfile.createBinaryProfile();
        protected final ConditionProfile rightProfile1 = ConditionProfile.createBinaryProfile();
        protected final ConditionProfile rightProfile2 = ConditionProfile.createBinaryProfile();

        public static AddNode create() {
            return StringBuiltinsFactory.AddNodeFactory.create();
        }

        @Specialization(guards = "!concatGuard(self, other)")
        String doSSSimple(String self, String other) {
            if (LazyString.length(self, leftProfile1, leftProfile2) == 0) {
                return other;
            }
            return self;
        }

        @Specialization(guards = "!concatGuard(self, other.getCharSequence())")
        Object doSSSimple(String self, PString other) {
            if (LazyString.length(self, leftProfile1, leftProfile2) == 0) {
                return other;
            }
            return self;
        }

        @Specialization(guards = "!concatGuard(self.getCharSequence(), other)")
        Object doSSSimple(PString self, String other) {
            if (LazyString.length(self.getCharSequence(), leftProfile1, leftProfile2) == 0) {
                return other;
            }
            return self;
        }

        @Specialization(guards = "!concatGuard(self.getCharSequence(), other.getCharSequence())")
        PString doSSSimple(PString self, PString other) {
            if (LazyString.length(self.getCharSequence(), leftProfile1, leftProfile2) == 0) {
                return other;
            }
            return self;
        }

        @Specialization(guards = "concatGuard(self.getCharSequence(), other)")
        Object doSS(PString self, String other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self.getCharSequence(), other, shortStringAppend);
        }

        @Specialization(guards = "concatGuard(self, other)")
        Object doSS(String self, String other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self, other, shortStringAppend);
        }

        @Specialization(guards = "concatGuard(self, other.getCharSequence())")
        Object doSS(String self, PString other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self, other.getCharSequence(), shortStringAppend);
        }

        @Specialization(guards = "concatGuard(self.getCharSequence(), other.getCharSequence())")
        Object doSS(PString self, PString other,
                        @Cached("createBinaryProfile()") ConditionProfile shortStringAppend) {
            return doIt(self.getCharSequence(), other.getCharSequence(), shortStringAppend);
        }

        private Object doIt(CharSequence left, CharSequence right, ConditionProfile shortStringAppend) {
            if (LazyString.UseLazyStrings) {
                int leftLength = LazyString.length(left, leftProfile1, leftProfile2);
                int rightLength = LazyString.length(right, rightProfile1, rightProfile2);
                int resultLength = leftLength + rightLength;
                if (resultLength >= LazyString.MinLazyStringLength) {
                    if (shortStringAppend.profile(leftLength == 1 || rightLength == 1)) {
                        return factory().createString(LazyString.createCheckedShort(left, right, resultLength));
                    } else {
                        return factory().createString(LazyString.createChecked(left, right, resultLength));
                    }
                }
            }
            return stringConcat(left, right);
        }

        @TruffleBoundary
        private static String stringConcat(CharSequence left, CharSequence right) {
            return left.toString() + right.toString();
        }

        @Specialization(guards = "!isString(other)")
        PNotImplemented doSO(@SuppressWarnings("unused") String self, @SuppressWarnings("unused") Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        @Specialization(guards = "!isString(other)")
        PNotImplemented doSO(@SuppressWarnings("unused") PString self, @SuppressWarnings("unused") Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        @SuppressWarnings("unused")
        @Fallback
        PNotImplemented doGeneric(Object self, Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        protected boolean concatGuard(CharSequence left, CharSequence right) {
            int leftLength = LazyString.length(left, leftProfile1, leftProfile2);
            int rightLength = LazyString.length(right, rightProfile1, rightProfile2);
            return leftLength > 0 && rightLength > 0;
        }
    }

    @Builtin(name = __RADD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class RAddNode extends PythonBinaryBuiltinNode {
        @Specialization
        static Object doAll(VirtualFrame frame, Object left, Object right,
                        @Cached AddNode addNode) {
            return addNode.execute(frame, right, left);
        }
    }

    abstract static class PrefixSuffixBaseNode extends PythonQuaternaryBuiltinNode {

        @Child private CastToSliceComponentNode castSliceComponentNode;
        @Child private GetObjectArrayNode getObjectArrayNode;
        @Child private CastToJavaStringNode castToJavaStringNode;

        // common and specialized cases --------------------

        @Specialization
        boolean doStringPrefixStartEnd(String self, String substr, int start, int end) {
            int len = self.length();
            return doIt(self, substr, adjustStart(start, len), adjustStart(end, len));
        }

        @Specialization
        boolean doStringPrefixStart(String self, String substr, int start, @SuppressWarnings("unused") PNone end) {
            int len = self.length();
            return doIt(self, substr, adjustStart(start, len), len);
        }

        @Specialization
        boolean doStringPrefix(String self, String substr, @SuppressWarnings("unused") PNone start, @SuppressWarnings("unused") PNone end) {
            return doIt(self, substr, 0, self.length());
        }

        @Specialization
        boolean doTuplePrefixStartEnd(String self, PTuple substrs, int start, int end) {
            int len = self.length();
            return doIt(self, substrs, adjustStart(start, len), adjustStart(end, len));
        }

        @Specialization
        boolean doTuplePrefixStart(String self, PTuple substrs, int start, @SuppressWarnings("unused") PNone end) {
            int len = self.length();
            return doIt(self, substrs, adjustStart(start, len), len);
        }

        @Specialization
        boolean doTuplePrefix(String self, PTuple substrs, @SuppressWarnings("unused") PNone start, @SuppressWarnings("unused") PNone end) {
            return doIt(self, substrs, 0, self.length());
        }

        // generic cases --------------------

        @Specialization(guards = "!isPTuple(substr)", replaces = {"doStringPrefixStartEnd", "doStringPrefixStart", "doStringPrefix"})
        boolean doObjectPrefixGeneric(VirtualFrame frame, Object self, Object substr, Object start, Object end,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castPrefixNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "startswith", self);
            int len = selfStr.length();
            int istart = adjustStart(castSlicePart(frame, start), len);
            int iend = PGuards.isPNone(end) ? len : adjustEnd(castSlicePart(frame, end), len);
            String prefixStr = castPrefixNode.cast(substr, INVALID_FIRST_ARG, "startswith", substr);
            return doIt(selfStr, prefixStr, istart, iend);
        }

        @Specialization(replaces = {"doTuplePrefixStartEnd", "doTuplePrefixStart", "doTuplePrefix"})
        boolean doTuplePrefixGeneric(VirtualFrame frame, Object self, PTuple substrs, Object start, Object end,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "startswith", self);
            int len = selfStr.length();
            int istart = adjustStart(castSlicePart(frame, start), len);
            int iend = PGuards.isPNone(end) ? len : adjustEnd(castSlicePart(frame, end), len);
            return doIt(selfStr, substrs, istart, iend);
        }

        // the actual operation; will be overridden by subclasses
        protected boolean doIt(String text, String substr, int start, int stop) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("should not reach");
        }

        private boolean doIt(String self, PTuple substrs, int start, int stop) {
            for (Object element : ensureGetObjectArrayNode().execute(substrs)) {
                String elementStr = castPrefix(element);
                if (elementStr != null) {
                    if (doIt(self, elementStr, start, stop)) {
                        return true;
                    }
                } else {
                    throw raise(TypeError, getErrorMessage(), element);
                }
            }
            return false;
        }

        protected String getErrorMessage() {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("should not reach");
        }

        // helper methods --------------------

        // for semantics, see macro 'ADJUST_INDICES' in CPython's 'unicodeobject.c'
        static int adjustStart(int start, int length) {
            if (start < 0) {
                int adjusted = start + length;
                return adjusted < 0 ? 0 : adjusted;
            }
            return start;
        }

        // for semantics, see macro 'ADJUST_INDICES' in CPython's 'unicodeobject.c'
        static int adjustEnd(int end, int length) {
            if (end > length) {
                return length;
            }
            return adjustStart(end, length);
        }

        private int castSlicePart(VirtualFrame frame, Object idx) {
            if (castSliceComponentNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                // None should map to 0, overflow to the maximum integer
                castSliceComponentNode = insert(CastToSliceComponentNode.create(0, Integer.MAX_VALUE));
            }
            return castSliceComponentNode.execute(frame, idx);
        }

        private GetObjectArrayNode ensureGetObjectArrayNode() {
            if (getObjectArrayNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getObjectArrayNode = insert(GetObjectArrayNodeGen.create());
            }
            return getObjectArrayNode;
        }

        private String castPrefix(Object prefix) {
            if (castToJavaStringNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                castToJavaStringNode = insert(CastToJavaStringNodeGen.create());
            }
            return castToJavaStringNode.execute(prefix);
        }

    }

    // str.startswith(prefix[, start[, end]])
    @Builtin(name = "startswith", minNumOfPositionalArgs = 2, parameterNames = {"self", "prefix", "start", "end"})
    @GenerateNodeFactory
    public abstract static class StartsWithNode extends PrefixSuffixBaseNode {

        private static final String INVALID_ELEMENT_TYPE = "tuple for startswith must only contain str, not %p";

        @Override
        protected boolean doIt(String text, String prefix, int start, int end) {
            // start and end must be normalized indices for 'text'
            assert start >= 0;
            assert end >= 0 && end <= text.length();

            if (end - start < prefix.length()) {
                return false;
            }
            return text.startsWith(prefix, start);
        }

        @Override
        protected String getErrorMessage() {
            return INVALID_ELEMENT_TYPE;
        }
    }

    // str.endswith(suffix[, start[, end]])
    @Builtin(name = "endswith", minNumOfPositionalArgs = 2, parameterNames = {"self", "suffix", "start", "end"})
    @GenerateNodeFactory
    public abstract static class EndsWithNode extends PrefixSuffixBaseNode {

        private static final String INVALID_ELEMENT_TYPE = "tuple for endswith must only contain str, not %p";

        @Override
        protected boolean doIt(String text, String suffix, int start, int end) {
            // start and end must be normalized indices for 'text'
            assert start >= 0;
            assert end >= 0 && end <= text.length();

            int suffixLen = suffix.length();
            if (end - start < suffixLen) {
                return false;
            }
            return text.startsWith(suffix, end - suffixLen);
        }

        @Override
        protected String getErrorMessage() {
            return INVALID_ELEMENT_TYPE;
        }
    }

    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class FindBaseNode extends PythonBuiltinNode {

        @Child private PythonObjectLibrary lib;

        private PythonObjectLibrary getLibrary() {
            if (lib == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                lib = insert(PythonObjectLibrary.getFactory().createDispatched(PythonOptions.getCallSiteInlineCacheMaxDepth()));
            }
            return lib;
        }

        private SliceInfo computeSlice(@SuppressWarnings("unused") VirtualFrame frame, int length, long start, long end) {
            PSlice tmpSlice = factory().createSlice(getLibrary().asSize(start), getLibrary().asSize(end), 1);
            return tmpSlice.computeIndices(length);
        }

        private SliceInfo computeSlice(VirtualFrame frame, int length, Object startO, Object endO) {
            int start = PGuards.isPNone(startO) ? PSlice.MISSING_INDEX : getLibrary().asSizeWithState(startO, PArguments.getThreadState(frame));
            int end = PGuards.isPNone(endO) ? PSlice.MISSING_INDEX : getLibrary().asSizeWithState(endO, PArguments.getThreadState(frame));
            return PSlice.computeIndices(start, end, 1, length);
        }

        @Specialization
        Object findString(String self, String str, @SuppressWarnings("unused") PNone start, @SuppressWarnings("unused") PNone end) {
            return find(self, str);
        }

        @Specialization
        Object findStringStart(VirtualFrame frame, String self, String str, long start, @SuppressWarnings("unused") PNone end) {
            int len = self.length();
            SliceInfo info = computeSlice(frame, len, start, len);
            if (info.length == 0) {
                return -1;
            }
            return findWithBounds(self, str, info.start, info.stop);
        }

        @Specialization
        Object findStringEnd(VirtualFrame frame, String self, String str, @SuppressWarnings("unused") PNone start, long end) {
            SliceInfo info = computeSlice(frame, self.length(), 0, end);
            if (info.length == 0) {
                return -1;
            }
            return findWithBounds(self, str, info.start, info.stop);
        }

        @Specialization
        Object findStringStartEnd(VirtualFrame frame, String self, String str, long start, long end) {
            SliceInfo info = computeSlice(frame, self.length(), start, end);
            if (info.length == 0) {
                return -1;
            }
            return findWithBounds(self, str, info.start, info.stop);
        }

        @Specialization
        Object findStringGeneric(VirtualFrame frame, String self, String str, Object start, Object end) {
            SliceInfo info = computeSlice(frame, self.length(), start, end);
            if (info.length == 0) {
                return -1;
            }
            return findWithBounds(self, str, info.start, info.stop);
        }

        @Specialization
        Object findGeneric(VirtualFrame frame, Object self, Object sub, Object start, Object end,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castSubNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "find", self);
            String subStr = castSubNode.cast(sub, MUST_BE_STR, sub);
            return findStringGeneric(frame, selfStr, subStr, start, end);
        }

        @SuppressWarnings("unused")
        protected int find(String self, String findStr) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("should not be reached");
        }

        @SuppressWarnings("unused")
        protected int findWithBounds(String self, String str, int start, int end) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("should not be reached");
        }
    }

    // str.rfind(str[, start[, end]])
    @Builtin(name = "rfind", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    public abstract static class RFindNode extends FindBaseNode {

        @Override
        @TruffleBoundary
        protected int find(String self, String findStr) {
            return self.lastIndexOf(findStr);
        }

        @Override
        @TruffleBoundary
        protected int findWithBounds(String self, String str, int start, int end) {
            int idx = self.lastIndexOf(str, end - str.length());
            return idx >= start ? idx : -1;
        }
    }

    // str.find(str[, start[, end]])
    @Builtin(name = "find", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    public abstract static class FindNode extends FindBaseNode {

        @Override
        @TruffleBoundary
        protected int find(String self, String findStr) {
            return self.indexOf(findStr);
        }

        @Override
        @TruffleBoundary
        protected int findWithBounds(String self, String str, int start, int end) {
            int idx = self.indexOf(str, start);
            return idx + str.length() <= end ? idx : -1;
        }
    }

    // str.join(iterable)
    @Builtin(name = "join", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class JoinNode extends PythonBinaryBuiltinNode {

        @Specialization
        static String join(VirtualFrame frame, Object self, Object iterable,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode,
                        @Cached JoinInternalNode join) {
            return join.execute(frame, castToJavaStringNode.cast(self, INVALID_RECEIVER, "join", self), iterable);
        }
    }

    // str.upper()
    @Builtin(name = "upper", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class UpperNode extends PythonBuiltinNode {

        @Specialization
        static String upper(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return toUpperCase(castToJavaStringNode.cast(self, INVALID_RECEIVER, "upper", self));
        }

        @TruffleBoundary
        private static String toUpperCase(String str) {
            return str.toUpperCase();
        }
    }

    // static str.maketrans()
    @Builtin(name = "maketrans", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 3, isStaticmethod = true)
    @GenerateNodeFactory
    public abstract static class MakeTransNode extends PythonTernaryBuiltinNode {

        @Specialization(guards = "!isNoValue(to)")
        PDict doString(VirtualFrame frame, Object from, Object to, @SuppressWarnings("unused") Object z,
                        @Cached CastToJavaStringCheckedNode castFromNode,
                        @Cached CastToJavaStringCheckedNode castToNode,
                        @Cached SetItemNode setItemNode) {

            String toStr = castToNode.cast(to, "argument 2 must be str, not %p", to);
            String fromStr = castFromNode.cast(from, "first maketrans argument must be a string if there is a second argument");
            if (fromStr.length() != toStr.length()) {
                throw raise(PythonBuiltinClassType.ValueError, "the first two maketrans arguments must have equal length");
            }

            PDict translation = factory().createDict();
            HashingStorage storage = translation.getDictStorage();
            for (int i = 0; i < fromStr.length(); i++) {
                int key = fromStr.charAt(i);
                int value = toStr.charAt(i);
                storage = setItemNode.execute(frame, storage, key, value);
            }
            translation.setDictStorage(storage);

            // TODO implement character deletion specified with 'z'

            return translation;
        }

        @Specialization(guards = "isNoValue(to)")
        @SuppressWarnings("unused")
        static PDict doDict(PDict from, Object to, Object z) {
            // TODO implement dict case; see CPython 'unicodeobject.c' function
            // 'unicode_maketrans_impl'
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("not yet implemented");
        }

        @Specialization(guards = {"!isDict(from)", "isNoValue(to)"})
        @SuppressWarnings("unused")
        PDict doDict(Object from, Object to, Object z) {
            throw raise(PythonBuiltinClassType.TypeError, "if you give only one argument to maketrans it must be a dict");
        }
    }

    // str.translate()
    @Builtin(name = "translate", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class TranslateNode extends PythonBuiltinNode {
        @Specialization
        static String doStringString(String self, String table) {
            char[] translatedChars = new char[self.length()];

            for (int i = 0; i < self.length(); i++) {
                char original = self.charAt(i);
                char translation = table.charAt(original);
                translatedChars[i] = translation;
            }

            return new String(translatedChars);
        }

        @Specialization
        static String doGeneric(VirtualFrame frame, Object self, Object table,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached GetItemNode getItemNode,
                        @Cached GetLazyClassNode getClassNode,
                        @Cached IsSubtypeNode isSubtypeNode,
                        @Cached SpliceNode spliceNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "translate", self);

            char[] translatedChars = new char[selfStr.length()];

            int offset = 0;
            for (int i = 0; i < selfStr.length(); i++) {
                char original = selfStr.charAt(i);
                Object translated = null;
                try {
                    translated = getItemNode.execute(frame, table, (int) original);
                } catch (PException e) {
                    if (!isSubtypeNode.execute(null, getClassNode.execute(e.getExceptionObject()), PythonBuiltinClassType.LookupError)) {
                        throw e;
                    }
                }
                if (PGuards.isNone(translated)) {
                    // untranslatable
                } else if (translated != null) {
                    int oldlen = translatedChars.length;
                    translatedChars = spliceNode.execute(translatedChars, i + offset, translated);
                    offset += translatedChars.length - oldlen;
                } else {
                    translatedChars[i + offset] = original;
                }
            }

            return new String(translatedChars);
        }
    }

    // str.lower()
    @Builtin(name = "lower", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class LowerNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return toLowerCase(castToJavaStringNode.cast(self, INVALID_RECEIVER, "lower", self));
        }

        @TruffleBoundary
        private static String toLowerCase(String self) {
            return self.toLowerCase();
        }
    }

    // str.capitalize()
    @Builtin(name = "capitalize", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class CapitalizeNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castToJavaStringNode) {
            return capitalize(castToJavaStringNode.cast(self, INVALID_RECEIVER, "capitalize", self));
        }

        @TruffleBoundary
        private static String capitalize(String self) {
            return self.substring(0, 1).toUpperCase() + self.substring(1);
        }
    }

    // str.rpartition
    @Builtin(name = "rpartition", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class RPartitionNode extends PythonBinaryBuiltinNode {

        @Specialization
        PList doString(String self, String sep,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            int lastIndexOf = self.lastIndexOf(sep);
            PList list = factory().createList();
            if (lastIndexOf == -1) {
                appendNode.execute(list, "");
                appendNode.execute(list, "");
                appendNode.execute(list, self);
            } else {
                appendNode.execute(list, PString.substring(self, 0, lastIndexOf));
                appendNode.execute(list, sep);
                appendNode.execute(list, PString.substring(self, lastIndexOf + sep.length()));
            }
            return list;
        }

        @Specialization(replaces = "doString")
        PList doGeneric(Object self, Object sep,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castSepNode,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "rpartition", self);
            String sepStr = castSepNode.cast(sep, MUST_BE_STR, sep);
            return doString(selfStr, sepStr, appendNode);
        }
    }

    // str.split
    @Builtin(name = "split", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    public abstract static class SplitNode extends PythonTernaryBuiltinNode {

        @Specialization
        @SuppressWarnings("unused")
        PList doStringWhitespace(String self, PNone sep, PNone maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            return splitfields(self, -1, appendNode);
        }

        @Specialization
        PList doStringSep(String self, String sep, @SuppressWarnings("unused") PNone maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            return doStringSepMaxsplit(self, sep, -1, appendNode);
        }

        @Specialization
        PList doStringSepMaxsplit(String self, String sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            if (sep.isEmpty()) {
                throw raise(ValueError, "empty separator");
            }
            int splits = maxsplit == -1 ? Integer.MAX_VALUE : maxsplit;

            PList list = factory().createList();
            int lastEnd = 0;
            while (splits > 0) {
                int nextIndex = PString.indexOf(self, sep, lastEnd);
                if (nextIndex == -1) {
                    break;
                }
                splits--;
                appendNode.execute(list, PString.substring(self, lastEnd, nextIndex));
                lastEnd = nextIndex + sep.length();
            }
            appendNode.execute(list, self.substring(lastEnd));
            return list;
        }

        @Specialization
        PList doStringMaxsplit(String self, @SuppressWarnings("unused") PNone sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode) {
            return splitfields(self, maxsplit, appendNode);
        }

        @Specialization(replaces = {"doStringWhitespace", "doStringSep", "doStringSepMaxsplit", "doStringMaxsplit"}, limit = "getCallSiteInlineCacheMaxDepth()")
        Object doGeneric(VirtualFrame frame, Object self, Object sep, Object maxsplit,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @CachedLibrary("maxsplit") PythonObjectLibrary lib,
                        @Cached CastToJavaStringCheckedNode castSepNode,
                        @Cached AppendNode appendNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "split", self);
            int imaxsplit = PGuards.isPNone(maxsplit) ? -1 : lib.asSizeWithState(maxsplit, PArguments.getThreadState(frame));
            if (PGuards.isPNone(sep)) {
                return splitfields(selfStr, imaxsplit, appendNode);
            } else {
                String sepStr = castSepNode.cast(sep, "Can't convert %p object to str implicitly", sep);
                return doStringSepMaxsplit(selfStr, sepStr, imaxsplit, appendNode);
            }
        }

        // See {@link PyString}
        private PList splitfields(String s, int maxsplit, AppendNode appendNode) {
            /*
             * Result built here is a list of split parts, exactly as required for s.split(None,
             * maxsplit). If there are to be n splits, there will be n+1 elements in L.
             */
            PList list = factory().createList();
            int length = s.length();
            int start = 0;
            int splits = 0;
            int index;

            int maxsplit2 = maxsplit;
            if (maxsplit2 < 0) {
                // Make all possible splits: there can't be more than:
                maxsplit2 = length;
            }

            // start is always the first character not consumed into a piece on the list
            while (start < length) {

                // Find the next occurrence of non-whitespace
                while (start < length) {
                    if (!PString.isWhitespace(s.charAt(start))) {
                        // Break leaving start pointing at non-whitespace
                        break;
                    }
                    start++;
                }

                if (start >= length) {
                    // Only found whitespace so there is no next segment
                    break;

                } else if (splits >= maxsplit2) {
                    // The next segment is the last and contains all characters up to the end
                    index = length;

                } else {
                    // The next segment runs up to the next next whitespace or end
                    for (index = start; index < length; index++) {
                        if (PString.isWhitespace(s.charAt(index))) {
                            // Break leaving index pointing at whitespace
                            break;
                        }
                    }
                }

                // Make a piece from start up to index
                appendNode.execute(list, PString.substring(s, start, index));
                splits++;

                // Start next segment search at that point
                start = index;
            }

            return list;
        }

    }

    // str.split
    @Builtin(name = "rsplit", minNumOfPositionalArgs = 1, parameterNames = {"self", "sep", "maxsplit"})
    @GenerateNodeFactory
    public abstract static class RSplitNode extends PythonTernaryBuiltinNode {

        @Specialization
        PList doStringWhitespace(VirtualFrame frame, String self, @SuppressWarnings("unused") PNone sep, @SuppressWarnings("unused") PNone maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode,
                        @Shared("reverseNode") @Cached ListReverseNode reverseNode) {
            return rsplitfields(frame, self, -1, appendNode, reverseNode);
        }

        @Specialization
        PList doStringSep(VirtualFrame frame, String self, String sep, @SuppressWarnings("unused") PNone maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode,
                        @Shared("reverseNode") @Cached ListReverseNode reverseNode) {
            return doStringSepMaxsplit(frame, self, sep, Integer.MAX_VALUE, appendNode, reverseNode);
        }

        @Specialization
        PList doStringSepMaxsplit(VirtualFrame frame, String self, String sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode,
                        @Shared("reverseNode") @Cached ListReverseNode reverseNode) {
            if (sep.length() == 0) {
                throw raise(ValueError, "empty separator");
            }
            PList list = factory().createList();
            int splits = 0;
            int end = self.length();
            String remainder = self;
            int sepLength = sep.length();
            while (splits < maxsplit) {
                int idx = remainder.lastIndexOf(sep);

                if (idx < 0) {
                    break;
                }

                appendNode.execute(list, self.substring(idx + sepLength, end));
                end = idx;
                splits++;
                remainder = remainder.substring(0, end);
            }

            appendNode.execute(list, remainder);
            reverseNode.execute(frame, list);
            return list;
        }

        @Specialization
        PList doStringMaxsplit(VirtualFrame frame, String self, @SuppressWarnings("unused") PNone sep, int maxsplit,
                        @Shared("appendNode") @Cached AppendNode appendNode,
                        @Shared("reverseNode") @Cached ListReverseNode reverseNode) {
            return rsplitfields(frame, self, maxsplit, appendNode, reverseNode);
        }

        @Specialization(replaces = {"doStringWhitespace", "doStringSep", "doStringSepMaxsplit", "doStringMaxsplit"}, limit = "getCallSiteInlineCacheMaxDepth()")
        Object doGeneric(VirtualFrame frame, Object self, Object sep, Object maxsplit,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @CachedLibrary("maxsplit") PythonObjectLibrary lib,
                        @Cached CastToJavaStringCheckedNode castSepNode,
                        @Cached AppendNode appendNode,
                        @Cached ListReverseNode reverseNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "rsplit", self);
            int imaxsplit = PGuards.isPNone(maxsplit) ? -1 : lib.asSizeWithState(maxsplit, PArguments.getThreadState(frame));
            if (PGuards.isPNone(sep)) {
                return rsplitfields(frame, selfStr, imaxsplit, appendNode, reverseNode);
            } else {
                String sepStr = castSepNode.cast(sep, "Can't convert %p object to str implicitly", sep);
                return doStringSepMaxsplit(frame, selfStr, sepStr, imaxsplit, appendNode, reverseNode);
            }
        }

        // See {@link PyString}
        private PList rsplitfields(VirtualFrame frame, String s, int maxsplit, AppendNode appendNode, ListReverseNode reverseNode) {
            /*
             * Result built here is a list of split parts, exactly as required for s.split(None,
             * maxsplit). If there are to be n splits, there will be n+1 elements in L.
             */
            PList list = factory().createList();
            int length = s.length();
            int end = length - 1;
            int splits = 0;
            int index;

            int maxsplit2 = maxsplit;
            if (maxsplit2 < 0) {
                // Make all possible splits: there can't be more than:
                maxsplit2 = length;
            }

            // start is always the first character not consumed into a piece on the list
            while (end > 0) {

                // Find the next occurrence of non-whitespace
                while (end >= 0) {
                    if (!PString.isWhitespace(s.codePointAt(end))) {
                        // Break leaving start pointing at non-whitespace
                        break;
                    }
                    end--;
                }

                if (end == 0) {
                    // Only found whitespace so there is no next segment
                    break;

                } else if (splits >= maxsplit2) {
                    // The next segment is the last and contains all characters up to the end
                    index = 0;

                } else {
                    // The next segment runs up to the next next whitespace or end
                    for (index = end; index >= 0; index--) {
                        if (PString.isWhitespace(s.codePointAt(index))) {
                            // Break leaving index pointing after the found whitespace
                            index++;
                            break;
                        }
                    }
                }

                // Make a piece from start up to index
                appendNode.execute(list, s.substring(index, end + 1));
                splits++;

                // Start next segment search at the whitespace
                end = index - 1;
            }

            reverseNode.execute(frame, list);
            return list;
        }
    }

    // str.splitlines([keepends])
    @Builtin(name = "splitlines", minNumOfPositionalArgs = 1, parameterNames = {"self", "keepends"})
    @GenerateNodeFactory
    public abstract static class SplitLinesNode extends PythonBinaryBuiltinNode {
        @Child private AppendNode appendNode = AppendNode.create();

        @Specialization
        PList doString(String self, @SuppressWarnings("unused") PNone keepends) {
            return doStringKeepends(self, false);
        }

        @Specialization
        PList doStringKeepends(String self, boolean keepends) {
            PList list = factory().createList();
            int lastEnd = 0;
            while (true) {
                int nextIndex = PString.indexOf(self, "\n", lastEnd);
                if (nextIndex == -1) {
                    break;
                }
                if (keepends) {
                    appendNode.execute(list, PString.substring(self, lastEnd, nextIndex + 1));
                } else {
                    appendNode.execute(list, PString.substring(self, lastEnd, nextIndex));
                }
                lastEnd = nextIndex + 1;
            }
            String remainder = PString.substring(self, lastEnd);
            if (!remainder.isEmpty()) {
                appendNode.execute(list, remainder);
            }
            return list;
        }

        @Specialization(replaces = {"doString", "doStringKeepends"})
        PList doGeneric(Object self, Object keepends,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaIntNode castToJavaIntNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "splitlines", self);
            boolean bKeepends = !PGuards.isPNone(keepends) && castToJavaIntNode.execute(keepends) != 0;
            return doStringKeepends(selfStr, bKeepends);
        }
    }

    // str.replace
    @Builtin(name = "replace", minNumOfPositionalArgs = 3, maxNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    public abstract static class ReplaceNode extends PythonBuiltinNode {

        @Specialization
        @TruffleBoundary
        static String doReplace(String self, String old, String with, @SuppressWarnings("unused") PNone maxCount) {
            return self.replace(old, with);
        }

        @TruffleBoundary
        @Specialization
        static String doReplace(String self, String old, String with, int maxCount) {
            StringBuilder sb = new StringBuilder(self);
            int prevIdx = 0;
            for (int i = 0; i < maxCount; i++) {
                int idx = sb.indexOf(old, prevIdx);
                if (idx == -1) {
                    // done
                    break;
                }

                sb.replace(idx, idx + old.length(), with);
                prevIdx = idx + with.length();
            }
            return sb.toString();
        }

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        static String doGeneric(VirtualFrame frame, Object self, Object old, Object with, Object maxCount,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @CachedLibrary("maxCount") PythonObjectLibrary lib) {

            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "replace", self);
            String oldStr = castSelfNode.cast(old, "replace() argument 1 must be str, not %p", "replace", old);
            String withStr = castSelfNode.cast(with, "replace() argument 2 must be str, not %p", "replace", with);
            if (PGuards.isPNone(maxCount)) {
                return doReplace(selfStr, oldStr, withStr, PNone.NO_VALUE);
            }
            int iMaxCount = lib.asSizeWithState(maxCount, PArguments.getThreadState(frame));
            return doReplace(selfStr, oldStr, withStr, iMaxCount);
        }
    }

    @Builtin(name = "strip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class StripNode extends PythonBinaryBuiltinNode {
        @Specialization
        static String doStringString(String self, String chars) {
            return StringUtils.strip(self, chars, StripKind.BOTH);
        }

        @Specialization
        static String doStringNone(String self, @SuppressWarnings("unused") PNone chars) {
            return self.trim();
        }

        @Specialization(replaces = {"doStringString", "doStringNone"})
        static String doGeneric(Object self, Object chars,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castCharsNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "strip", self);
            if (PGuards.isPNone(chars)) {
                return doStringNone(selfStr, PNone.NO_VALUE);
            }
            String charsStr = castSelfNode.cast(chars, "replace() argument 1 must be str, not %p", "strip", chars);
            return doStringString(selfStr, charsStr);
        }
    }

    @Builtin(name = "rstrip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class RStripNode extends PythonBinaryBuiltinNode {
        @Specialization
        static String doStringString(String self, String chars) {
            return StringUtils.strip(self, chars, StripKind.RIGHT);
        }

        @Specialization
        static String doStringNone(String self, @SuppressWarnings("unused") PNone chars) {
            return StringUtils.strip(self, StripKind.RIGHT);
        }

        @Specialization(replaces = {"doStringString", "doStringNone"})
        static String doGeneric(Object self, Object chars,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castCharsNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "rstrip", self);
            if (PGuards.isPNone(chars)) {
                return doStringNone(selfStr, PNone.NO_VALUE);
            }
            String charsStr = castCharsNode.cast(chars, "replace() argument 1 must be str, not %p", "rstrip", chars);
            return doStringString(selfStr, charsStr);
        }
    }

    @Builtin(name = "lstrip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LStripNode extends PythonBuiltinNode {
        @Specialization
        static String doStringString(String self, String chars) {
            return StringUtils.strip(self, chars, StripKind.LEFT);
        }

        @Specialization
        static String doStringNone(String self, @SuppressWarnings("unused") PNone chars) {
            return StringUtils.strip(self, StripKind.LEFT);
        }

        @Specialization(replaces = {"doStringString", "doStringNone"})
        static String doGeneric(Object self, Object chars,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castCharsNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "lstrip", self);
            if (PGuards.isPNone(chars)) {
                return doStringNone(selfStr, PNone.NO_VALUE);
            }
            String charsStr = castCharsNode.cast(chars, "replace() argument 1 must be str, not %p", "lstrip", chars);
            return doStringString(selfStr, charsStr);
        }
    }

    @Builtin(name = SpecialMethodNames.__LEN__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class LenNode extends PythonUnaryBuiltinNode {
        @Specialization
        static int len(Object self,
                        @Cached StringLenNode stringLenNode) {
            return stringLenNode.execute(self);
        }
    }

    @Builtin(name = "index", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    public abstract static class IndexNode extends PythonQuaternaryBuiltinNode {

        @Specialization
        int doStringString(String self, String substr, @SuppressWarnings("unused") PNone start, @SuppressWarnings("unused") PNone end,
                        @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            return indexOf(self, substr, 0, self.length(), errorProfile);
        }

        @Specialization
        int doStringStringStart(String self, String substr, int start, @SuppressWarnings("unused") PNone end,
                        @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            return indexOf(self, substr, start, self.length(), errorProfile);
        }

        @Specialization
        int doStringStringStartEnd(String self, String substr, int start, int end,
                        @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            return indexOf(self, substr, start, end, errorProfile);
        }

        @Specialization
        int doGeneric(VirtualFrame frame, Object self, Object sub, Object start, Object end,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castSubNode,
                        @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "index", self);
            String subStr = castSubNode.cast(sub, MUST_BE_STR, sub);
            int istart = PGuards.isPNone(start) ? 0 : lib.asSizeWithState(start, PArguments.getThreadState(frame));
            int iend = PGuards.isPNone(end) ? selfStr.length() : lib.asSizeWithState(end, PArguments.getThreadState(frame));
            return indexOf(selfStr, subStr, istart, iend, errorProfile);
        }

        private int indexOf(String self, String substr, int start, int end, ConditionProfile errorProfile) {
            int idx = PString.indexOf(self, substr, start);
            if (errorProfile.profile(idx > end || idx < 0)) {
                throw raise(ValueError, "substring not found");
            } else {
                return idx;
            }
        }
    }

    // This is only used during bootstrap and then replaced with Python code
    @Builtin(name = "encode", minNumOfPositionalArgs = 1, parameterNames = {"self", "encoding", "errors"})
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    public abstract static class EncodeNode extends PythonBuiltinNode {

        @Specialization
        Object doString(String self, @SuppressWarnings("unused") PNone encoding, @SuppressWarnings("unused") PNone errors) {
            return encodeString(self, "utf-8", "strict");
        }

        @Specialization
        Object doStringEncoding(String self, String encoding, @SuppressWarnings("unused") PNone errors) {
            return encodeString(self, encoding, "strict");
        }

        @Specialization
        Object doStringErrors(String self, @SuppressWarnings("unused") PNone encoding, String errors) {
            return encodeString(self, "utf-8", errors);
        }

        @Specialization
        Object doStringEncodingErrors(String self, String encoding, String errors) {
            return encodeString(self, encoding, errors);
        }

        @Specialization
        Object doGeneric(Object self, Object encoding, Object errors,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Cached CastToJavaStringCheckedNode castEncodingNode,
                        @Cached CastToJavaStringCheckedNode castErrorsNode) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "index", self);
            String encodingStr = PGuards.isPNone(encoding) ? "utf-8" : castEncodingNode.cast(encoding, MUST_BE_STR, encoding);
            String errorsStr = PGuards.isPNone(errors) ? "strict" : castErrorsNode.cast(errors, MUST_BE_STR, errors);
            return encodeString(selfStr, encodingStr, errorsStr);
        }

        @TruffleBoundary
        private Object encodeString(String self, String encoding, String errors) {
            CodingErrorAction errorAction;
            switch (errors) {
                case "ignore":
                    errorAction = CodingErrorAction.IGNORE;
                    break;
                case "replace":
                    errorAction = CodingErrorAction.REPLACE;
                    break;
                default:
                    errorAction = CodingErrorAction.REPORT;
                    break;
            }

            Charset cs;
            try {
                cs = Charset.forName(encoding);
            } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
                throw raise(LookupError, "unknown encoding: %s", encoding);
            }
            try {
                ByteBuffer encoded = cs.newEncoder().onMalformedInput(errorAction).onUnmappableCharacter(errorAction).encode(CharBuffer.wrap(self));
                int n = encoded.remaining();
                byte[] data = new byte[n];
                encoded.get(data);
                return factory().createBytes(data);
            } catch (CharacterCodingException e) {
                throw raise(UnicodeEncodeError, e);
            }
        }
    }

    @Builtin(name = SpecialMethodNames.__MUL__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class MulNode extends PythonBinaryBuiltinNode {

        @Specialization
        String doStringInt(String left, int right) {
            if (right <= 0) {
                return "";
            }
            return repeatString(left, right);
        }

        @Specialization(limit = "1")
        String doStringLong(String left, long right,
                        @Exclusive @CachedLibrary("right") PythonObjectLibrary lib) {
            return doStringInt(left, lib.asSize(right));
        }

        @Specialization
        String doStringObject(VirtualFrame frame, String left, Object right,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Cached IsBuiltinClassProfile typeErrorProfile) {
            try {
                return doStringInt(left, lib.asSizeWithState(right, PArguments.getThreadState(frame)));
            } catch (PException e) {
                e.expect(PythonBuiltinClassType.OverflowError, typeErrorProfile);
                throw raise(MemoryError);
            }
        }

        @Specialization
        Object doGeneric(VirtualFrame frame, Object self, Object times,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Cached IsBuiltinClassProfile typeErrorProfile) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, "index", self);
            return doStringObject(frame, selfStr, times, lib, typeErrorProfile);
        }

        @TruffleBoundary
        private String repeatString(String left, int times) {
            try {
                StringBuilder str = new StringBuilder(Math.multiplyExact(left.length(), times));
                for (int i = 0; i < times; i++) {
                    str.append(left);
                }
                return str.toString();
            } catch (OutOfMemoryError | ArithmeticException e) {
                throw raise(MemoryError);
            }
        }
    }

    @Builtin(name = __RMUL__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class RMulNode extends MulNode {
    }

    @Builtin(name = __MOD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class ModNode extends PythonBinaryBuiltinNode {

        @Specialization
        Object doStringObject(VirtualFrame frame, String self, Object right,
                        @Shared("callNode") @Cached CallNode callNode,
                        @Shared("getClassNode") @Cached GetLazyClassNode getClassNode,
                        @Shared("lookupAttrNode") @Cached LookupAttributeInMRONode.Dynamic lookupAttrNode,
                        @Shared("getItemNode") @Cached("create(__GETITEM__)") LookupAndCallBinaryNode getItemNode,
                        @Shared("context") @CachedContext(PythonLanguage.class) PythonContext context) {
            Object state = IndirectCallContext.enter(frame, context, this);
            try {
                return new StringFormatter(context.getCore(), self).format(right, callNode, (object, key) -> lookupAttrNode.execute(getClassNode.execute(object), key), getItemNode);
            } finally {
                IndirectCallContext.exit(frame, context, state);
            }
        }

        @Specialization
        Object doGeneric(VirtualFrame frame, Object self, Object right,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Shared("callNode") @Cached CallNode callNode,
                        @Shared("getClassNode") @Cached GetLazyClassNode getClassNode,
                        @Shared("lookupAttrNode") @Cached LookupAttributeInMRONode.Dynamic lookupAttrNode,
                        @Shared("getItemNode") @Cached("create(__GETITEM__)") LookupAndCallBinaryNode getItemNode,
                        @Shared("context") @CachedContext(PythonLanguage.class) PythonContext context) {

            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, __MOD__, self);
            return doStringObject(frame, selfStr, right, callNode, getClassNode, lookupAttrNode, getItemNode, context);
        }
    }

    @Builtin(name = "isascii", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsAsciiNode extends PythonUnaryBuiltinNode {
        private static final CharsetEncoder asciiEncoder = StandardCharsets.US_ASCII.newEncoder();

        @Specialization
        @TruffleBoundary
        boolean doString(String self) {
            return asciiEncoder.canEncode(self);
        }

        @Specialization(replaces = "doString")
        boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isascii", self));
        }
    }

    @Builtin(name = "isalnum", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsAlnumNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                if (!Character.isLetterOrDigit(self.codePointAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isalnum", self));
        }
    }

    @Builtin(name = "isalpha", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsAlphaNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                if (!Character.isLetter(self.codePointAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isalpha", self));
        }
    }

    @Builtin(name = "isdecimal", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsDecimalNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                if (!Character.isDigit(self.codePointAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isdecimal", self));
        }
    }

    @Builtin(name = "isdigit", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsDigitNode extends IsDecimalNode {
    }

    @Builtin(name = "isnumeric", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsNumericNode extends IsDecimalNode {
    }

    @Builtin(name = "isidentifier", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsIdentifierNode extends PythonUnaryBuiltinNode {
        @Specialization
        boolean doString(String self) {
            return getCore().getParser().isIdentifier(getCore(), self);
        }

        @Specialization(replaces = "doString")
        boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isidentifier", self));
        }
    }

    @Builtin(name = "islower", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsLowerNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            int uncased = 0;
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                int codePoint = self.codePointAt(i);
                if (!Character.isLowerCase(codePoint)) {
                    if (Character.toLowerCase(codePoint) == Character.toUpperCase(codePoint)) {
                        uncased++;
                    } else {
                        return false;
                    }
                }
            }
            return uncased == 0 || self.length() > uncased;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "islower", self));
        }
    }

    @Builtin(name = "isprintable", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsPrintableNode extends PythonUnaryBuiltinNode {
        @TruffleBoundary
        private static boolean isPrintableChar(int i) {
            if (Character.isISOControl(i)) {
                return false;
            }
            Character.UnicodeBlock block = Character.UnicodeBlock.of(i);
            return block != null && block != Character.UnicodeBlock.SPECIALS;
        }

        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            for (int i = 0; i < self.length(); i++) {
                if (!isPrintableChar(self.codePointAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isprintable", self));
        }
    }

    @Builtin(name = "isspace", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsSpaceNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                if (!Character.isWhitespace(self.codePointAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isspace", self));
        }
    }

    @Builtin(name = "istitle", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsTitleNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            boolean hasContent = false;
            boolean expectLower = false;
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                int codePoint = self.codePointAt(i);
                if (!expectLower) {
                    if (Character.isTitleCase(codePoint) || Character.isUpperCase(codePoint)) {
                        expectLower = true;
                        hasContent = true;
                    } else if (Character.isLowerCase(codePoint)) {
                        return false;
                    }
                    // uncased characters are allowed
                } else {
                    if (Character.isTitleCase(codePoint) || Character.isUpperCase(codePoint)) {
                        return false;
                    } else if (!Character.isLowerCase(codePoint)) {
                        // we expect another title start after an uncased character
                        expectLower = false;
                    }
                }
            }
            return hasContent;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "istitle", self));
        }
    }

    @Builtin(name = "isupper", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IsUpperNode extends PythonUnaryBuiltinNode {
        @Specialization
        @TruffleBoundary
        static boolean doString(String self) {
            int uncased = 0;
            if (self.length() == 0) {
                return false;
            }
            for (int i = 0; i < self.length(); i++) {
                int codePoint = self.codePointAt(i);
                if (!Character.isUpperCase(codePoint)) {
                    if (Character.toLowerCase(codePoint) == Character.toUpperCase(codePoint)) {
                        uncased++;
                    } else {
                        return false;
                    }
                }
            }
            return uncased == 0 || self.length() > uncased;
        }

        @Specialization(replaces = "doString")
        static boolean doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "isupper", self));
        }
    }

    @Builtin(name = "zfill", minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class ZFillNode extends PythonBinaryBuiltinNode {

        public abstract String executeObject(VirtualFrame frame, String self, Object x);

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        static String doGeneric(VirtualFrame frame, Object self, Object width,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @CachedLibrary("width") PythonObjectLibrary lib) {
            return zfill(castSelfNode.cast(self, INVALID_RECEIVER, "zfill", self), lib.asSizeWithState(width, PArguments.getThreadState(frame)));

        }

        private static String zfill(String self, int width) {
            int len = self.length();
            if (len >= width) {
                return self;
            }
            char[] chars = new char[width];
            int nzeros = width - len;
            int i = 0;
            int sStart = 0;
            if (len > 0) {
                char start = self.charAt(0);
                if (start == '+' || start == '-') {
                    chars[0] = start;
                    i += 1;
                    nzeros++;
                    sStart = 1;
                }
            }
            for (; i < nzeros; i++) {
                chars[i] = '0';
            }
            self.getChars(sStart, len, chars, i);
            return new String(chars);
        }
    }

    @Builtin(name = "title", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class TitleNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doString(String self) {
            return doTitle(self);
        }

        @Specialization
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doTitle(castSelfNode.cast(self, INVALID_RECEIVER, "title", self));
        }

        @TruffleBoundary
        private static String doTitle(String self) {
            boolean shouldBeLowerCase = false;
            boolean translated;
            StringBuilder converted = new StringBuilder();
            for (int offset = 0; offset < self.length();) {
                int ch = self.codePointAt(offset);
                translated = false;
                if (Character.isAlphabetic(ch)) {
                    if (shouldBeLowerCase) {
                        // Should be lower case
                        if (Character.isUpperCase(ch)) {
                            translated = true;
                            if (ch < 256) {
                                converted.append((char) Character.toLowerCase(ch));
                            } else {
                                String origPart = new String(Character.toChars(ch));
                                String changedPart = origPart.toLowerCase();
                                converted.append(changedPart);
                            }
                        }
                    } else {
                        // Should be upper case
                        if (Character.isLowerCase(ch)) {
                            translated = true;
                            if (ch < 256) {
                                converted.append((char) Character.toUpperCase(ch));
                            } else {
                                String origPart = new String(Character.toChars(ch));
                                String changedPart = origPart.toUpperCase();
                                if (origPart.length() < changedPart.length()) {
                                    // the original char was mapped to more chars ->
                                    // we need to make upper case just the first one
                                    changedPart = doTitle(changedPart);
                                }
                                converted.append(changedPart);
                            }
                        }
                    }
                    // And this was a letter
                    shouldBeLowerCase = true;
                } else {
                    // This was not a letter
                    shouldBeLowerCase = false;
                }
                if (!translated) {
                    if (ch < 256) {
                        converted.append((char) ch);
                    } else {
                        converted.append(Character.toChars(ch));
                    }
                }
                offset += Character.charCount(ch);
            }
            return converted.toString();
        }
    }

    @Builtin(name = "center", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class CenterNode extends PythonBuiltinNode {

        @Specialization(guards = "isNoValue(fill)")
        String doStringInt(String self, int width, @SuppressWarnings("unused") PNone fill) {
            return make(self, width, " ");
        }

        @Specialization(guards = "fill.codePointCount(0, fill.length()) == 1")
        String doStringIntString(String self, int width, String fill) {
            return make(self, width, fill);
        }

        @Specialization
        String doStringObjectObject(VirtualFrame frame, String self, Object width, Object fill,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Shared("castFillNode") @Cached CastToJavaStringCheckedNode castFillNode,
                        @Shared("errorProfile") @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            String fillStr = PGuards.isNoValue(fill) ? " " : castFillNode.cast(fill, "", fill);
            if (errorProfile.profile(fillStr.codePointCount(0, fillStr.length()) != 1)) {
                throw raise(TypeError, "The fill character must be exactly one character long");
            }
            return make(self, lib.asSizeWithState(width, PArguments.getThreadState(frame)), fillStr);
        }

        @Specialization
        String doGeneric(VirtualFrame frame, Object self, Object width, Object fill,
                        @Cached CastToJavaStringCheckedNode castSelfNode,
                        @Shared("castToIndexNode") @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary lib,
                        @Shared("castFillNode") @Cached CastToJavaStringCheckedNode castFillNode,
                        @Shared("errorProfile") @Cached("createBinaryProfile()") ConditionProfile errorProfile) {
            String selfStr = castSelfNode.cast(self, INVALID_RECEIVER, __ITER__, self);
            return doStringObjectObject(frame, selfStr, width, fill, lib, castFillNode, errorProfile);
        }

        @TruffleBoundary
        protected String make(String self, int width, String fill) {
            int fillChar = parseCodePoint(fill);
            int len = width - self.length();
            if (len <= 0) {
                return self;
            }
            int half = len / 2;
            if (len % 2 > 0 && width % 2 > 0) {
                half += 1;
            }

            return padding(half, fillChar) + self + padding(len - half, fillChar);
        }

        @TruffleBoundary
        protected static String padding(int len, int codePoint) {
            int[] result = new int[len];
            for (int i = 0; i < len; i++) {
                result[i] = codePoint;
            }
            return new String(result, 0, len);
        }

        @TruffleBoundary
        protected static int parseCodePoint(String fillchar) {
            if (fillchar == null) {
                return ' ';
            }
            return fillchar.codePointAt(0);
        }
    }

    @Builtin(name = "ljust", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class LJustNode extends CenterNode {

        @Override
        @TruffleBoundary
        protected String make(String self, int width, String fill) {
            int fillChar = parseCodePoint(fill);
            int len = width - self.length();
            if (len <= 0) {
                return self;
            }
            return self + padding(len, fillChar);
        }

    }

    @Builtin(name = "rjust", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class RJustNode extends CenterNode {

        @Override
        @TruffleBoundary
        protected String make(String self, int width, String fill) {
            int fillChar = parseCodePoint(fill);
            int len = width - self.length();
            if (len <= 0) {
                return self;
            }
            return padding(len, fillChar) + self;
        }

    }

    @Builtin(name = __GETITEM__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    public abstract static class StrGetItemNode extends PythonBinaryBuiltinNode {

        @Specialization
        public String doString(String primary, PSlice slice) {
            SliceInfo info = slice.computeIndices(primary.length());
            final int start = info.start;
            int stop = info.stop;
            int step = info.step;

            if (step > 0 && stop < start) {
                stop = start;
            }
            if (step == 1) {
                return getSubString(primary, start, stop);
            } else {
                char[] newChars = new char[info.length];
                int j = 0;
                for (int i = start; j < info.length; i += step) {
                    newChars[j++] = primary.charAt(i);
                }

                return new String(newChars);
            }
        }

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        public String doString(VirtualFrame frame, String primary, Object idx,
                        @CachedLibrary("idx") PythonObjectLibrary lib) {
            int index = lib.asSizeWithState(idx, PArguments.getThreadState(frame));
            try {
                if (index < 0) {
                    index += primary.length();
                }
                return charAtToString(primary, index);
            } catch (StringIndexOutOfBoundsException | ArithmeticException e) {
                throw raise(IndexError, "IndexError: string index out of range");
            }
        }

        @SuppressWarnings("unused")
        @Fallback
        Object doGeneric(Object self, Object other) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }

        @TruffleBoundary
        private static String getSubString(String origin, int start, int stop) {
            char[] chars = new char[stop - start];
            origin.getChars(start, stop, chars, 0);
            return new String(chars);
        }

        @TruffleBoundary
        private static String charAtToString(String primary, int index) {
            char charactor = primary.charAt(index);
            return new String(new char[]{charactor});
        }
    }

    @Builtin(name = __ITER__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class IterNode extends PythonUnaryBuiltinNode {
        @Specialization
        PStringIterator doString(String self) {
            return factory().createStringIterator(self);
        }

        @Specialization(replaces = "doString")
        PStringIterator doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, __ITER__, self));
        }
    }

    @Builtin(name = "casefold", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class CasefoldNode extends PythonUnaryBuiltinNode {

        @Specialization
        static String doString(String self) {
            // TODO(fa) implement properly using 'unicodedata_db' (see 'unicodeobject.c' function
            // 'unicode_casefold_impl')
            return self.toLowerCase();
        }

        @Specialization(replaces = "doString")
        static String doGeneric(Object self,
                        @Cached CastToJavaStringCheckedNode castSelfNode) {
            return doString(castSelfNode.cast(self, INVALID_RECEIVER, "casefold", self));
        }
    }
}
