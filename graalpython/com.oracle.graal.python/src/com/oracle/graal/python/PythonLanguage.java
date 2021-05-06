/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates.
 * Copyright (c) 2015, Regents of the University of California
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
package com.oracle.graal.python;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionValues;

import com.oracle.graal.python.builtins.Python3Core;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.ellipsis.PEllipsis;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.builtins.objects.object.PythonObject;
import com.oracle.graal.python.builtins.objects.type.PythonManagedClass;
import com.oracle.graal.python.builtins.objects.type.TypeBuiltins;
import com.oracle.graal.python.nodes.BuiltinNames;
import com.oracle.graal.python.nodes.HiddenAttributes;
import com.oracle.graal.python.nodes.NodeFactory;
import com.oracle.graal.python.nodes.call.InvokeNode;
import com.oracle.graal.python.nodes.control.TopLevelExceptionHandler;
import com.oracle.graal.python.nodes.expression.BinaryArithmetic;
import com.oracle.graal.python.nodes.expression.ExpressionNode;
import com.oracle.graal.python.nodes.expression.InplaceArithmetic;
import com.oracle.graal.python.nodes.expression.TernaryArithmetic;
import com.oracle.graal.python.nodes.expression.UnaryArithmetic;
import com.oracle.graal.python.parser.PythonParserImpl;
import com.oracle.graal.python.runtime.GilNode;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.PythonCore;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.PythonParser.ParserMode;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.graal.python.runtime.interop.InteropMap;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.graal.python.util.Function;
import com.oracle.graal.python.util.PFunctionArgsFinder;
import com.oracle.graal.python.util.PythonUtils;
import com.oracle.graal.python.util.Supplier;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.ContextThreadLocal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.ExecutableNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.Source.SourceBuilder;

@TruffleLanguage.Registration(id = PythonLanguage.ID, //
                name = PythonLanguage.NAME, //
                version = PythonLanguage.VERSION, //
                characterMimeTypes = PythonLanguage.MIME_TYPE, //
                dependentLanguages = {"nfi", "llvm"}, //
                interactive = true, internal = false, //
                contextPolicy = TruffleLanguage.ContextPolicy.SHARED, //
                fileTypeDetectors = PythonFileDetector.class)
@ProvidedTags({
                StandardTags.CallTag.class,
                StandardTags.StatementTag.class,
                StandardTags.RootTag.class,
                StandardTags.RootBodyTag.class,
                StandardTags.TryBlockTag.class,
                StandardTags.ExpressionTag.class,
                StandardTags.ReadVariableTag.class,
                StandardTags.WriteVariableTag.class,
                DebuggerTags.AlwaysHalt.class
})
public final class PythonLanguage extends TruffleLanguage<PythonContext> {
    public static final String ID = "python";
    public static final String NAME = "Python";
    public static final int MAJOR = 3;
    public static final int MINOR = 8;
    public static final int MICRO = 5;
    // Note: update hexversion in sys.py when updating release level
    public static final String RELEASE_LEVEL = "alpha";
    public static final String VERSION = MAJOR + "." + MINOR + "." + MICRO;
    // Rarely updated version of the C API, we should take it from the imported CPython version
    public static final int API_VERSION = 1013;

    public static final String MIME_TYPE = "text/x-python";
    public static final String EXTENSION = ".py";
    public static final String[] DEFAULT_PYTHON_EXTENSIONS = new String[]{EXTENSION, ".pyc"};

    private static final TruffleLogger LOGGER = TruffleLogger.getLogger(ID, PythonLanguage.class);

    public final Assumption singleContextAssumption = Truffle.getRuntime().createAssumption("Only a single context is active");

    /**
     * This assumption will be valid if all contexts are single-threaded. Hence, it will be
     * invalidated as soon as at least one context has been initialized for multi-threading.
     */
    public final Assumption singleThreadedAssumption = Truffle.getRuntime().createAssumption("Only a single thread is active");

    private final NodeFactory nodeFactory;
    private final ConcurrentHashMap<String, RootCallTarget> builtinCallTargetCache = new ConcurrentHashMap<>();
    /**
     * A thread-safe map that maps arithmetic operators (i.e.
     * {@link com.oracle.graal.python.nodes.expression.UnaryArithmetic},
     * {@link com.oracle.graal.python.nodes.expression.BinaryArithmetic},
     * {@link com.oracle.graal.python.nodes.expression.TernaryArithmetic}, and
     * {@link com.oracle.graal.python.nodes.expression.InplaceArithmetic}) to call targets. Use this
     * map to retrieve a singleton instance (per engine) such that proper AST sharing is possible.
     */
    private final AtomicReference<ConcurrentHashMap<Object, WeakReference<RootCallTarget>>> arithmeticOpCallTargetCacheRef = new AtomicReference<>();

    private final Shape emptyShape = Shape.newBuilder().allowImplicitCastIntToDouble(false).allowImplicitCastIntToLong(true).shapeFlags(0).propertyAssumptions(true).build();
    @CompilationFinal(dimensions = 1) private final Shape[] builtinTypeInstanceShapes = new Shape[PythonBuiltinClassType.VALUES.length];

    @CompilationFinal(dimensions = 1) private static final Object[] CONTEXT_INSENSITIVE_SINGLETONS = new Object[]{PNone.NONE, PNone.NO_VALUE, PEllipsis.INSTANCE, PNotImplemented.NOT_IMPLEMENTED};

    /**
     * Named semaphores are shared between all processes in a system, and they persist until the
     * system is shut down, unless explicitly removed. We interpret this as meaning they all exist
     * globally per language instance, that is, they are shared between different Contexts in the
     * same engine.
     */
    public final ConcurrentHashMap<String, Semaphore> namedSemaphores = new ConcurrentHashMap<>();

    @CompilationFinal(dimensions = 1) private volatile Object[] engineOptionsStorage;
    @CompilationFinal private volatile OptionValues engineOptions;

    /** A shared shape for the C symbol cache (lazily initialized). */
    private Shape cApiSymbolCache;
    private Shape hpySymbolCache;

    private final ContextThreadLocal<PythonContext.PythonThreadState> threadState = createContextThreadLocal(PythonContext.PythonThreadState::new);

    public final ConcurrentHashMap<String, HiddenKey> typeHiddenKeys = new ConcurrentHashMap<>(TypeBuiltins.INITIAL_HIDDEN_TYPE_KEYS);

    public static int getNumberOfSpecialSingletons() {
        return CONTEXT_INSENSITIVE_SINGLETONS.length;
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    public static int getSingletonNativeWrapperIdx(Object obj) {
        for (int i = 0; i < CONTEXT_INSENSITIVE_SINGLETONS.length; i++) {
            if (CONTEXT_INSENSITIVE_SINGLETONS[i] == obj) {
                return i;
            }
        }
        return -1;
    }

    public PythonLanguage() {
        print("totally arbitrary change");
        this.nodeFactory = NodeFactory.create(this);
    }

    public NodeFactory getNodeFactory() {
        return nodeFactory;
    }

    @Override
    protected void finalizeContext(PythonContext context) {
        context.finalizeContext();
        super.finalizeContext(context);
    }

    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return PythonOptions.areOptionsCompatible(firstOptions, newOptions);
    }

    @Override
    protected boolean patchContext(PythonContext context, Env newEnv) {
        if (!areOptionsCompatible(context.getEnv().getOptions(), newEnv.getOptions())) {
            PythonCore.writeInfo("Cannot use preinitialized context.");
            return false;
        }
        context.initializeHomeAndPrefixPaths(newEnv, getLanguageHome());
        PythonCore.writeInfo("Using preinitialized context.");
        context.patch(newEnv);
        return true;
    }

    @Override
    protected PythonContext createContext(Env env) {
        Python3Core newCore = new Python3Core(new PythonParserImpl(env), env.isNativeAccessAllowed());
        final PythonContext context = new PythonContext(this, env, newCore, threadState);
        context.initializeHomeAndPrefixPaths(env, getLanguageHome());

        Object[] engineOptionsUnroll = this.engineOptionsStorage;
        if (engineOptionsUnroll == null) {
            this.engineOptionsStorage = engineOptionsUnroll = PythonOptions.createEngineOptionValuesStorage(env);
        } else {
            assert Arrays.equals(engineOptionsUnroll, PythonOptions.createEngineOptionValuesStorage(env)) : "invalid engine options";
        }

        OptionValues options = this.engineOptions;
        if (options == null) {
            this.engineOptions = PythonOptions.createEngineOptions(env);
        } else {
            assert areOptionsCompatible(options, PythonOptions.createEngineOptions(env)) : "invalid engine options";
        }
        return context;
    }

    public <T> T getEngineOption(OptionKey<T> key) {
        assert engineOptions != null;
        if (CompilerDirectives.inInterpreter()) {
            return engineOptions.get(key);
        } else {
            return PythonOptions.getOptionUnrolling(this.engineOptionsStorage, PythonOptions.getEngineOptionKeys(), key);
        }
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return PythonOptions.DESCRIPTORS;
    }

    @Override
    protected void initializeContext(PythonContext context) {
        context.initialize();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        PythonContext context = getCurrentContext(PythonLanguage.class);
        PythonCore core = context.getCore();
        Source source = request.getSource();
        CompilerDirectives.transferToInterpreter();
        if (core.isInitialized()) {
            context.initializeMainModule(source.getPath());
        }
        if (!request.getArgumentNames().isEmpty()) {
            return PythonUtils.getOrCreateCallTarget(parseWithArguments(request));
        }
        RootNode root = doParse(context, source);
        if (core.isInitialized()) {
            return PythonUtils.getOrCreateCallTarget(new TopLevelExceptionHandler(this, root));
        } else {
            return PythonUtils.getOrCreateCallTarget(root);
        }
    }

    private RootNode doParse(PythonContext context, Source source) {
        ParserMode mode;
        if (source.isInteractive()) {
            if (context.getOption(PythonOptions.TerminalIsInteractive)) {
                // if we run through our own launcher, the sys.__displayhook__ would provide the
                // printing
                mode = ParserMode.Statement;
            } else {
                // if we're not run through our own launcher, the embedder will expect the normal
                // Truffle printing
                mode = ParserMode.InteractiveStatement;
            }
        } else {
            // by default we assume a module
            mode = ParserMode.File;
        }
        PythonCore pythonCore = context.getCore();
        try {
            return (RootNode) pythonCore.getParser().parse(mode, 0, pythonCore, source, null, null);
        } catch (PException e) {
            // handle PException during parsing (PIncompleteSourceException will propagate through)
            PythonUtils.getOrCreateCallTarget(new TopLevelExceptionHandler(this, e)).call();
            throw e;
        }
    }

    private RootNode parseWithArguments(ParsingRequest request) {
        final String[] argumentNames = request.getArgumentNames().toArray(new String[request.getArgumentNames().size()]);
        final Source source = request.getSource();
        CompilerDirectives.transferToInterpreter();
        final PythonLanguage lang = this;
        final RootNode executableNode = new RootNode(lang) {
            @Child private RootNode rootNode;
            @Child private GilNode gilNode;

            protected Object[] preparePArguments(VirtualFrame frame) {
                int argumentsLength = frame.getArguments().length;
                Object[] arguments = PArguments.create(argumentsLength);
                PArguments.setGlobals(arguments, new PDict(lang));
                PythonUtils.arraycopy(frame.getArguments(), 0, arguments, PArguments.USER_ARGUMENTS_OFFSET, argumentsLength);
                return arguments;
            }

            @Override
            @TruffleBoundary
            public Object execute(VirtualFrame frame) {
                PythonContext context = lookupContextReference(PythonLanguage.class).get();
                assert context != null;
                if (!context.isInitialized()) {
                    context.initialize();
                }
                if (rootNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    parse(context, frame);
                }
                if (gilNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    gilNode = insert(GilNode.create());
                }
                boolean wasAcquired = gilNode.acquire();
                try {
                    Object[] args = preparePArguments(frame);
                    Object result = InvokeNode.invokeUncached(rootNode.getCallTarget(), args);
                    return result;
                } finally {
                    gilNode.release(wasAcquired);
                }
            }

            private void parse(PythonContext context, VirtualFrame frame) {
                CompilerAsserts.neverPartOfCompilation();
                rootNode = (RootNode) context.getCore().getParser().parse(ParserMode.WithArguments, 0, context.getCore(), source, frame, argumentNames);
            }
        };
        return executableNode;
    }

    @Override
    protected ExecutableNode parse(InlineParsingRequest request) {
        CompilerDirectives.transferToInterpreter();
        final Source source = request.getSource();
        final MaterializedFrame requestFrame = request.getFrame();
        final ExecutableNode executableNode = new ExecutableNode(this) {
            @CompilationFinal private ContextReference<PythonContext> contextRef;
            @CompilationFinal private volatile PythonContext cachedContext;
            @Child private GilNode gilNode;
            @Child private ExpressionNode expression;

            @Override
            public Object execute(VirtualFrame frame) {
                if (contextRef == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    contextRef = lookupContextReference(PythonLanguage.class);
                }
                PythonContext context = contextRef.get();
                assert context != null && context.isInitialized();
                PythonContext cachedCtx = cachedContext;
                if (cachedCtx == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    parseAndCache(context);
                    cachedCtx = context;
                }
                if (gilNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    gilNode = insert(GilNode.create());
                }
                boolean wasAcquired = gilNode.acquire();
                try {
                    Object result;
                    if (context == cachedCtx) {
                        result = expression.execute(frame);
                    } else {
                        result = parseAndEval(context, frame.materialize());
                    }
                    return result;
                } finally {
                    gilNode.release(wasAcquired);
                }
            }

            private void parseAndCache(PythonContext context) {
                CompilerAsserts.neverPartOfCompilation();
                expression = insert(parseInline(source, context, requestFrame));
                cachedContext = context;
            }

            @TruffleBoundary
            private Object parseAndEval(PythonContext context, MaterializedFrame frame) {
                ExpressionNode fragment = parseInline(source, context, frame);
                return fragment.execute(frame);
            }
        };
        return executableNode;
    }

    @TruffleBoundary
    protected static ExpressionNode parseInline(Source code, PythonContext context, MaterializedFrame lexicalContextFrame) {
        PythonCore pythonCore = context.getCore();
        return (ExpressionNode) pythonCore.getParser().parse(ParserMode.InlineEvaluation, 0, pythonCore, code, lexicalContextFrame, null);
    }

    @Override
    protected Object getLanguageView(PythonContext context, Object value) {
        assert !(value instanceof PythonAbstractObject);
        PythonObjectFactory factory = PythonObjectFactory.getUncached();
        InteropLibrary interopLib = InteropLibrary.getFactory().getUncached(value);
        try {
            if (interopLib.isBoolean(value)) {
                if (interopLib.asBoolean(value)) {
                    return context.getCore().getTrue();
                } else {
                    return context.getCore().getFalse();
                }
            } else if (interopLib.isString(value)) {
                return factory.createString(interopLib.asString(value));
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                // TODO: (tfel) once the interop protocol allows us to
                // distinguish fixed point from floating point reliably, we can
                // remove this branch
                return factory.createInt(interopLib.asLong(value));
            } else if (value instanceof Float || value instanceof Double) {
                // TODO: (tfel) once the interop protocol allows us to
                // distinguish fixed point from floating point reliably, we can
                // remove this branch
                return factory.createFloat(interopLib.asDouble(value));
            } else if (interopLib.fitsInLong(value)) {
                return factory.createInt(interopLib.asLong(value));
            } else if (interopLib.fitsInDouble(value)) {
                return factory.createFloat(interopLib.asDouble(value));
            } else {
                return new ForeignLanguageView(value);
            }
        } catch (UnsupportedMessageException e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException(e);
        }
    }

    @ExportLibrary(value = InteropLibrary.class, delegateTo = "delegate")
    static class ForeignLanguageView implements TruffleObject {
        final Object delegate;

        ForeignLanguageView(Object delegate) {
            this.delegate = delegate;
        }

        @ExportMessage
        @TruffleBoundary
        String toDisplayString(boolean allowSideEffects,
                        @CachedLibrary("this.delegate") InteropLibrary lib) {
            return "<foreign '" + lib.toDisplayString(delegate, allowSideEffects) + "'>";
        }

        @ExportMessage
        @SuppressWarnings("static-method")
        boolean hasLanguage() {
            return true;
        }

        @ExportMessage
        Class<? extends TruffleLanguage<?>> getLanguage() {
            return PythonLanguage.class;
        }
    }

    public String getHome() {
        return getLanguageHome();
    }

    public static PythonLanguage getCurrent() {
        return getCurrentLanguage(PythonLanguage.class);
    }

    public static PythonContext getContext() {
        return getCurrentContext(PythonLanguage.class);
    }

    public static PythonCore getCore() {
        return getCurrentContext(PythonLanguage.class).getCore();
    }

    @Override
    protected boolean isVisible(PythonContext context, Object value) {
        return value != PNone.NONE && value != PNone.NO_VALUE;
    }

    @Override
    @TruffleBoundary
    // Remove in GR-26206
    @SuppressWarnings("deprecation")
    protected Iterable<com.oracle.truffle.api.Scope> findLocalScopes(PythonContext context, Node node, Frame frame) {
        ArrayList<com.oracle.truffle.api.Scope> scopes = new ArrayList<>();
        for (com.oracle.truffle.api.Scope s : super.findLocalScopes(context, node, frame)) {
            if (frame == null) {
                PFunctionArgsFinder argsFinder = new PFunctionArgsFinder(node);

                com.oracle.truffle.api.Scope.Builder scopeBuilder = com.oracle.truffle.api.Scope.newBuilder(s.getName(), s.getVariables()).node(s.getNode()).receiver(s.getReceiverName(),
                                s.getReceiver()).rootInstance(
                                                s.getRootInstance()).arguments(argsFinder.collectArgs());

                scopes.add(scopeBuilder.build());
            } else {
                scopes.add(s);
            }
        }

        if (frame != null) {
            PythonObject globals = PArguments.getGlobalsSafe(frame);
            if (globals != null) {
                scopes.add(com.oracle.truffle.api.Scope.newBuilder("globals()", scopeFromObject(globals)).build());
            }
            Frame generatorFrame = PArguments.getGeneratorFrameSafe(frame);
            if (generatorFrame != null) {
                for (com.oracle.truffle.api.Scope s : super.findLocalScopes(context, node, generatorFrame)) {
                    scopes.add(s);
                }
            }
        }
        return scopes;
    }

    private static InteropMap scopeFromObject(PythonObject globals) {
        if (globals instanceof PDict) {
            return InteropMap.fromPDict((PDict) globals);
        } else {
            return InteropMap.fromPythonObject(globals);
        }
    }

    @Override
    // Remove in GR-26206
    @SuppressWarnings("deprecation")
    protected Iterable<com.oracle.truffle.api.Scope> findTopScopes(PythonContext context) {
        ArrayList<com.oracle.truffle.api.Scope> scopes = new ArrayList<>();
        if (context.getBuiltins() != null) {
            // false during initialization
            scopes.add(com.oracle.truffle.api.Scope.newBuilder(BuiltinNames.__MAIN__, context.getMainModule()).build());
            scopes.add(com.oracle.truffle.api.Scope.newBuilder(BuiltinNames.BUILTINS, scopeFromObject(context.getBuiltins())).build());
        }
        return scopes;
    }

    @TruffleBoundary
    public static TruffleLogger getLogger(Class<?> clazz) {
        return TruffleLogger.getLogger(ID, clazz);
    }

    /**
     * Loggers that should report any known incompatibility with CPython, which is silently ignored
     * in order to be able to continue the execution. Example is setting the stack size limit: it
     * would be too drastic measure to raise error, because the program may continue and work
     * correctly even if it is ignored.
     *
     * The logger name is prefixed with "compatibility" such that
     * {@code --log.python.compatibility.level=LEVEL} can turn on compatibility related logging for
     * all classes.
     */
    @TruffleBoundary
    public static TruffleLogger getCompatibilityLogger(Class<?> clazz) {
        return TruffleLogger.getLogger(ID, "compatibility." + clazz.getName());
    }

    public static Source newSource(PythonContext ctxt, String src, String name, boolean mayBeFile) {
        try {
            SourceBuilder sourceBuilder = null;
            if (mayBeFile) {
                try {
                    TruffleFile truffleFile = ctxt.getPublicTruffleFileRelaxed(name, PythonLanguage.DEFAULT_PYTHON_EXTENSIONS);
                    if (truffleFile.exists()) {
                        // XXX: (tfel): We don't know if the expression has anything to do with the
                        // filename that's given. We would really have to compare the entire
                        // contents, but as a first approximation, we compare the content lengths.
                        // We override the contents of the source builder with the given source
                        // regardless.
                        if (src.length() == truffleFile.size() || src.getBytes().length == truffleFile.size()) {
                            sourceBuilder = Source.newBuilder(ID, truffleFile);
                            sourceBuilder.content(src);
                        }
                    }
                } catch (SecurityException | IOException e) {
                    sourceBuilder = null;
                }
            }
            if (sourceBuilder == null) {
                sourceBuilder = Source.newBuilder(ID, src, name);
            }
            return newSource(ctxt, sourceBuilder);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Source newSource(PythonContext ctxt, TruffleFile src, String name) throws IOException {
        return newSource(ctxt, Source.newBuilder(ID, src).name(name));
    }

    private static Source newSource(PythonContext ctxt, SourceBuilder srcBuilder) throws IOException {
        boolean coreIsInitialized = ctxt.getCore().isInitialized();
        boolean internal = !coreIsInitialized && !ctxt.getLanguage().getEngineOption(PythonOptions.ExposeInternalSources);
        if (internal) {
            srcBuilder.internal(true);
        }
        return srcBuilder.build();
    }

    @Override
    protected void initializeMultipleContexts() {
        super.initializeMultipleContexts();
        singleContextAssumption.invalidate();
    }

    private final ConcurrentHashMap<String, CallTarget> cachedCode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String[]> cachedCodeModulePath = new ConcurrentHashMap<>();

    @TruffleBoundary
    public CallTarget cacheCode(String filename, Supplier<CallTarget> createCode) {
        return cachedCode.computeIfAbsent(filename, f -> {
            LOGGER.log(Level.FINEST, () -> "Caching CallTarget for " + filename);
            return createCode.get();
        });
    }

    @TruffleBoundary
    public String[] cachedCodeModulePath(String name) {
        return cachedCodeModulePath.get(name);
    }

    @TruffleBoundary
    public boolean hasCachedCode(String name) {
        return cachedCode.get(name) != null;
    }

    @TruffleBoundary
    public CallTarget cacheCode(String filename, Supplier<CallTarget> createCode, String[] modulepath) {
        CallTarget ct = cacheCode(filename, createCode);
        cachedCodeModulePath.computeIfAbsent(filename, t -> modulepath);
        return ct;
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        if (singleThreaded) {
            return super.isThreadAccessAllowed(thread, singleThreaded);
        }
        return true;
    }

    @Override
    protected void initializeMultiThreading(PythonContext context) {
        if (singleThreadedAssumption.isValid()) {
            singleThreadedAssumption.invalidate();
            context.initializeMultiThreading();
        }
    }

    @Override
    protected void initializeThread(PythonContext context, Thread thread) {
        context.attachThread(thread);
    }

    @Override
    protected void disposeThread(PythonContext context, Thread thread) {
        context.disposeThread(thread);
    }

    public RootCallTarget getOrComputeBuiltinCallTarget(String key, Supplier<RootNode> supplier) {
        return builtinCallTargetCache.computeIfAbsent(key, (k) -> PythonUtils.getOrCreateCallTarget(supplier.get()));
    }

    public Shape getEmptyShape() {
        return emptyShape;
    }

    public Shape getShapeForClass(PythonManagedClass klass) {
        if (singleContextAssumption.isValid()) {
            return Shape.newBuilder(getEmptyShape()).addConstantProperty(HiddenAttributes.CLASS, klass, 0).build();
        } else {
            return getEmptyShape();
        }
    }

    public static Shape getShapeForClassWithoutDict(PythonManagedClass klass) {
        return Shape.newBuilder(klass.getInstanceShape()).shapeFlags(PythonObject.HAS_SLOTS_BUT_NO_DICT_FLAG).build();
    }

    public Shape getBuiltinTypeInstanceShape(PythonBuiltinClassType type) {
        int ordinal = type.ordinal();
        Shape shape = builtinTypeInstanceShapes[ordinal];
        if (shape == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Shape.DerivedBuilder shapeBuilder = Shape.newBuilder(getEmptyShape()).addConstantProperty(HiddenAttributes.CLASS, type, 0);
            if (!type.isBuiltinWithDict()) {
                shapeBuilder.shapeFlags(PythonObject.HAS_SLOTS_BUT_NO_DICT_FLAG);
            }
            shape = shapeBuilder.build();
            builtinTypeInstanceShapes[ordinal] = shape;
        }
        return shape;
    }

    /**
     * Retrieve a call target for the given {@link UnaryArithmetic} operator. If the no such call
     * target exists yet, it will be created lazily. This method is thread-safe and should be used
     * for all contexts in this engine to enable AST sharing.
     */
    @TruffleBoundary
    public RootCallTarget getOrCreateUnaryArithmeticCallTarget(UnaryArithmetic unaryOperator) {
        return getOrCreateArithmeticCallTarget(unaryOperator, unaryOperator::createCallTarget);
    }

    /**
     * Retrieve a call target for the given {@link BinaryArithmetic} operator. If the no such call
     * target exists yet, it will be created lazily. This method is thread-safe and should be used
     * for all contexts in this engine to enable AST sharing.
     */
    @TruffleBoundary
    public RootCallTarget getOrCreateBinaryArithmeticCallTarget(BinaryArithmetic unaryOperator) {
        return getOrCreateArithmeticCallTarget(unaryOperator, unaryOperator::createCallTarget);
    }

    /**
     * Retrieve a call target for the given {@link TernaryArithmetic} operator. If the no such call
     * target exists yet, it will be created lazily. This method is thread-safe and should be used
     * for all contexts in this engine to enable AST sharing.
     */
    @TruffleBoundary
    public RootCallTarget getOrCreateTernaryArithmeticCallTarget(TernaryArithmetic unaryOperator) {
        return getOrCreateArithmeticCallTarget(unaryOperator, unaryOperator::createCallTarget);
    }

    /**
     * Retrieve a call target for the given {@link InplaceArithmetic} operator. If the no such call
     * target exists yet, it will be created lazily. This method is thread-safe and should be used
     * for all contexts in this engine to enable AST sharing.
     */
    @TruffleBoundary
    public RootCallTarget getOrCreateInplaceArithmeticCallTarget(InplaceArithmetic unaryOperator) {
        return getOrCreateArithmeticCallTarget(unaryOperator, unaryOperator::createCallTarget);
    }

    private RootCallTarget getOrCreateArithmeticCallTarget(Object arithmeticOperator, Function<PythonLanguage, RootCallTarget> supplier) {
        CompilerAsserts.neverPartOfCompilation();
        ConcurrentHashMap<Object, WeakReference<RootCallTarget>> arithmeticOpCallTargetCache = arithmeticOpCallTargetCacheRef.get();
        if (arithmeticOpCallTargetCache == null) {
            arithmeticOpCallTargetCache = arithmeticOpCallTargetCacheRef.updateAndGet((v) -> {
                // IMPORTANT: only create a new instance if we still see 'null'; otherwise we would
                // overwrite the update of a different thread
                if (v == null) {
                    return new ConcurrentHashMap<>();
                }
                return v;
            });
        }

        WeakReference<RootCallTarget> ctRef = arithmeticOpCallTargetCache.compute(arithmeticOperator, (k, v) -> {
            RootCallTarget cachedCallTarget = v != null ? v.get() : null;
            if (cachedCallTarget == null) {
                return new WeakReference<>(supplier.apply(this));
            }
            return v;
        });

        RootCallTarget callTarget = ctRef.get();
        if (callTarget == null) {
            // Bad luck: we ensured that there is a mapping in the cache but the weak value got
            // collected before we could strongly reference it. Now, we need to be conservative and
            // create the call target eagerly, hold a strong reference to it until we've put it into
            // the map.
            final RootCallTarget callTargetToCache = supplier.apply(this);
            callTarget = callTargetToCache;
            arithmeticOpCallTargetCache.computeIfAbsent(arithmeticOperator, (k) -> new WeakReference<>(callTargetToCache));
        }
        assert callTarget != null;
        return callTarget;
    }

    /**
     * Returns the shape used for the C API symbol cache.
     */
    @TruffleBoundary
    public synchronized Shape getCApiSymbolCacheShape() {
        if (cApiSymbolCache == null) {
            cApiSymbolCache = Shape.newBuilder().build();
        }
        return cApiSymbolCache;
    }

    /**
     * Returns the shape used for the HPy API symbol cache.
     */
    @TruffleBoundary
    public synchronized Shape getHPySymbolCacheShape() {
        if (hpySymbolCache == null) {
            hpySymbolCache = Shape.newBuilder().build();
        }
        return hpySymbolCache;
    }
}
