/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.runtime;

import com.oracle.graal.python.builtins.objects.exception.OSErrorEnum;
import com.oracle.graal.python.runtime.NativeLibrary.InvokeNativeFunction;
import com.oracle.graal.python.runtime.NativeLibrary.NFIBackend;
import com.oracle.graal.python.runtime.NativeLibrary.NativeFunction;
import com.oracle.graal.python.runtime.NativeLibrary.TypedNativeLibrary;
import com.oracle.graal.python.runtime.PosixSupportLibrary.Buffer;
import com.oracle.graal.python.runtime.PosixSupportLibrary.PosixException;
import com.oracle.graal.python.runtime.PosixSupportLibrary.PosixPath;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.profiles.BranchProfile;

/**
 * Implementation that invokes the native POSIX functions directly using NFI. This requires either
 * that the native access is allowed or to configure managed LLVM backend for NFI.
 */
@ExportLibrary(PosixSupportLibrary.class)
public final class NFIPosixSupport extends PosixSupport {
    private static final String SUPPORTING_NATIVE_LIB_NAME = "libposix";

    private static final int F_GETFD = 1;
    private static final int F_SETFD = 2;
    private static final int F_DUPFD_CLOEXEC = 1030;

    private static final int FD_CLOEXEC = 1;

    enum NativeFunctions implements NativeFunction {
        get_errno("():sint32"),
        set_errno("(sint32):void"),
        call_strerror("(sint32, [sint8], sint32):void"),
        call_getpid("():sint64"),
        call_umask("(sint64):sint64"),
        call_open_at("(sint32, [sint8], sint32, sint32):sint32"),
        call_close("(sint32):sint32"),
        call_read("(sint32, [sint8], uint64):sint64"),
        call_write("(sint32, [sint8], uint64):sint64"),
        call_fcntl_int("(sint32, sint32, sint32):sint32"),
        call_dup2("(sint32, sint32):sint32"),
        call_dup3("(sint32, sint32, sint32):sint32"),
        call_pipe2("([sint32], sint32):sint32"),
        call_lseek("(sint32, sint64, sint32):sint64"),
        call_ftruncate("(sint32, sint64):sint32");

        private final String signature;

        NativeFunctions(String signature) {
            this.signature = signature;
        }

        @Override
        public String signature() {
            return signature;
        }
    }

    private final PythonContext context;
    private final TypedNativeLibrary<NativeFunctions> lib;

    private NFIPosixSupport(PythonContext context, NFIBackend backend) {
        this.context = context;
        lib = NativeLibrary.create(SUPPORTING_NATIVE_LIB_NAME, NativeFunctions.values(), backend,
                        "You can switch to pure Java emulated POSIX support, " +
                                        "which does not require native access privilege, by passing option " +
                                        "'--python.PosixModuleBackend=java'.");
    }

    public static NFIPosixSupport createNative(PythonContext context) {
        return new NFIPosixSupport(context, NFIBackend.NATIVE);
    }

    public static NFIPosixSupport createLLVM(PythonContext context) {
        return new NFIPosixSupport(context, NFIBackend.LLVM);
    }

    @ExportMessage
    public String strerror(int errorCode,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) {
        // From man pages: The GNU C Library uses a buffer of 1024 characters for strerror().
        // This buffer size therefore should be sufficient to avoid an ERANGE error when calling
        // strerror_r().
        byte[] buf = new byte[1024];
        invokeNode.call(lib, NativeFunctions.call_strerror, errorCode, wrap(buf), buf.length);
        return cStringToJavaString(buf);
    }

    @ExportMessage
    public long getpid(@Shared("invoke") @Cached InvokeNativeFunction invokeNode) {
        return invokeNode.callLong(lib, NativeFunctions.call_getpid);
    }

    @ExportMessage
    public long umask(long mask,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) {
        long result = invokeNode.callLong(lib, NativeFunctions.call_umask, mask);
        if (result < 0) {
            // TODO call errno() and raise OS error
            // create helper method for this (like CPython's posix_error)
        }
        return result;
    }

    @ExportMessage
    public int openAt(int dirFd, PosixPath pathname, int flags, int mode,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode,
                    @Shared("async") @Cached BranchProfile asyncProfile) throws PosixException {
        while (true) {
            int fd = invokeNode.callInt(lib, NativeFunctions.call_open_at, dirFd, pathToCString(pathname), flags, mode);
            if (fd >= 0) {
                return fd;
            }
            int errno = getErrno(invokeNode);
            if (errno != OSErrorEnum.EINTR.getNumber()) {
                throw newPosixException(invokeNode, errno, pathname.originalObject);
            }
            context.triggerAsyncActions(null, asyncProfile);
        }
    }

    @ExportMessage
    public void close(int fd,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        if (invokeNode.callInt(lib, NativeFunctions.call_close, fd) < 0) {
            throw getErrnoAndThrowPosixException(invokeNode);
        }
    }

    @ExportMessage
    public Buffer read(int fd, long length,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode,
                    @Shared("async") @Cached BranchProfile asyncProfile) throws PosixException {
        Buffer buffer = Buffer.allocate(length);
        while (true) {
            setErrno(invokeNode, 0);
            long n = invokeNode.callLong(lib, NativeFunctions.call_read, fd, wrap(buffer), length);
            if (n >= 0) {
                return buffer.withLength(n);
            }
            int errno = getErrno(invokeNode);
            if (errno != OSErrorEnum.EINTR.getNumber()) {
                throw newPosixException(invokeNode, errno);
            }
            context.triggerAsyncActions(null, asyncProfile);
        }
    }

    @ExportMessage
    public long write(int fd, Buffer data,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode,
                    @Shared("async") @Cached BranchProfile asyncProfile) throws PosixException {
        while (true) {
            setErrno(invokeNode, 0);
            long n = invokeNode.callLong(lib, NativeFunctions.call_write, fd, wrap(data), data.length);
            if (n >= 0) {
                return n;
            }
            int errno = getErrno(invokeNode);
            if (errno != OSErrorEnum.EINTR.getNumber()) {
                throw newPosixException(invokeNode, errno);
            }
            context.triggerAsyncActions(null, asyncProfile);
        }
    }

    @ExportMessage
    public int dup(int fd,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        int newFd = invokeNode.callInt(lib, NativeFunctions.call_fcntl_int, fd, F_DUPFD_CLOEXEC, 0);
        if (newFd < 0) {
            throw getErrnoAndThrowPosixException(invokeNode);
        }
        return newFd;
    }

    @ExportMessage
    public int dup2(int fd, int fd2, boolean inheritable,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        int newFd;
        if (!inheritable) {
            newFd = invokeNode.callInt(lib, NativeFunctions.call_dup3, fd, fd2, PosixSupportLibrary.O_CLOEXEC);
        } else {
            newFd = invokeNode.callInt(lib, NativeFunctions.call_dup2, fd, fd2);
        }
        if (newFd < 0) {
            throw getErrnoAndThrowPosixException(invokeNode);
        }
        return newFd;
    }

    @ExportMessage
    public boolean getInheritable(int fd,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        return (getFdFlags(fd, invokeNode) & FD_CLOEXEC) == 0;
    }

    @ExportMessage
    public void setInheritable(int fd, boolean inheritable,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        int oldFlags = getFdFlags(fd, invokeNode);
        int newFlags;
        if (inheritable) {
            newFlags = oldFlags & ~FD_CLOEXEC;
        } else {
            newFlags = oldFlags | FD_CLOEXEC;
        }
        if (newFlags != oldFlags) {
            int res = invokeNode.callInt(lib, NativeFunctions.call_fcntl_int, fd, F_SETFD, newFlags);
            if (res < 0) {
                throw getErrnoAndThrowPosixException(invokeNode);
            }
        }
    }

    @ExportMessage
    public int[] pipe(
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        int[] fds = new int[2];
        int res = invokeNode.callInt(lib, NativeFunctions.call_pipe2, context.getEnv().asGuestValue(fds), PosixSupportLibrary.O_CLOEXEC);
        if (res != 0) {
            throw getErrnoAndThrowPosixException(invokeNode);
        }
        return fds;
    }

    @ExportMessage
    public long lseek(int fd, long offset, int how,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode) throws PosixException {
        long res = invokeNode.callLong(lib, NativeFunctions.call_lseek, fd, offset, how);
        if (res < 0) {
            throw getErrnoAndThrowPosixException(invokeNode);
        }
        return res;
    }

    @ExportMessage
    public void ftruncate(int fd, long length,
                    @Shared("invoke") @Cached InvokeNativeFunction invokeNode,
                    @Shared("async") @Cached BranchProfile asyncProfile) throws PosixException {
        while (true) {
            int res = invokeNode.callInt(lib, NativeFunctions.call_ftruncate, fd, length);
            if (res == 0) {
                return;
            }
            int errno = getErrno(invokeNode);
            if (errno != OSErrorEnum.EINTR.getNumber()) {
                throw newPosixException(invokeNode, errno);
            }
            context.triggerAsyncActions(null, asyncProfile);
        }
    }

    private int getFdFlags(int fd, InvokeNativeFunction invokeNode) throws PosixException {
        int flags = invokeNode.callInt(lib, NativeFunctions.call_fcntl_int, fd, F_GETFD, 0);
        if (flags < 0) {
            throw getErrnoAndThrowPosixException(invokeNode);
        }
        return flags;
    }

    private int getErrno(InvokeNativeFunction invokeNode) {
        return invokeNode.callInt(lib, NativeFunctions.get_errno);
    }

    private void setErrno(InvokeNativeFunction invokeNode, int errno) {
        invokeNode.call(lib, NativeFunctions.set_errno, errno);
    }

    private PosixException getErrnoAndThrowPosixException(InvokeNativeFunction invokeNode) throws PosixException {
        throw newPosixException(invokeNode, getErrno(invokeNode));
    }

    private PosixException newPosixException(InvokeNativeFunction invokeNode, int errno) throws PosixException {
        throw new PosixException(errno, strerror(errno, invokeNode));
    }

    private PosixException newPosixException(InvokeNativeFunction invokeNode, int errno, Object filename) throws PosixException {
        throw new PosixException(errno, strerror(errno, invokeNode), filename);
    }

    private Object wrap(byte[] bytes) {
        return context.getEnv().asGuestValue(bytes);
    }

    private Object wrap(Buffer buffer) {
        return context.getEnv().asGuestValue(buffer.data);
    }

    private static String cStringToJavaString(byte[] buf) {
        for (int i = 0; i < buf.length; ++i) {
            if (buf[i] == 0) {
                return PythonUtils.newString(buf, 0, i);
            }
        }
        return PythonUtils.newString(buf);
    }

    private Object pathToCString(PosixPath path) {
        return wrap(nullTerminate(path.path));
    }

    private static byte[] nullTerminate(byte[] str) {
        byte[] terminated = new byte[str.length + 1];
        PythonUtils.arraycopy(str, 0, terminated, 0, str.length);
        return terminated;
    }
}
