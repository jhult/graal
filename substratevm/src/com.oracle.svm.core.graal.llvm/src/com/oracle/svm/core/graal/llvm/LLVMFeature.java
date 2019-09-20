/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.SubstrateOptions.CompilerBackend;
import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.llvm.LLVMUtils.TargetSpecific;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.java.LoadExceptionObjectNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.TargetGraphBuilderPlugins;
import org.graalvm.compiler.replacements.llvm.LLVMGraphBuilderPlugins;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.impl.InternalPlatform;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.graal.GraalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.code.SubstrateBackendFactory;
import com.oracle.svm.core.graal.code.SubstrateLoweringProviderFactory;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.graal.nodes.ExceptionStateNode;
import com.oracle.svm.core.graal.nodes.ReadExceptionObjectNode;
import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.nodes.CFunctionEpilogueNode;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.c.util.FileUtils;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.image.NativeImageCodeCache;
import com.oracle.svm.hosted.image.NativeImageCodeCacheFactory;
import com.oracle.svm.hosted.image.NativeImageHeap;
import com.oracle.svm.hosted.meta.HostedMethod;

@AutomaticFeature
@CLibrary("m")
@Platforms({Platform.LINUX.class, InternalPlatform.LINUX_AND_JNI.class, Platform.DARWIN.class, InternalPlatform.DARWIN_AND_JNI.class})
public class LLVMFeature implements Feature, GraalFeature {

    private static HostedMethod personalityStub;
    public static HostedMethod retrieveExceptionMethod;

    public static final int SPECIAL_REGISTER_COUNT;
    public static final int THREAD_POINTER_INDEX;
    public static final int HEAP_BASE_INDEX;

    static {
        int firstArgumentOffset = 0;
        THREAD_POINTER_INDEX = (SubstrateOptions.MultiThreaded.getValue()) ? firstArgumentOffset++ : -1;
        HEAP_BASE_INDEX = (SubstrateOptions.SpawnIsolates.getValue()) ? firstArgumentOffset++ : -1;
        SPECIAL_REGISTER_COUNT = firstArgumentOffset;
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CompilerBackend.getValue().equals("llvm");
    }

    public static HostedMethod getPersonalityStub() {
        return personalityStub;
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        if (!LLVMOptions.CustomLLC.hasBeenSet()) {
            checkLLVMVersion();
        }

        ImageSingletons.add(SubstrateBackendFactory.class, new SubstrateBackendFactory() {
            @Override
            public SubstrateBackend newBackend(Providers newProviders) {
                return new SubstrateLLVMBackend(newProviders);
            }
        });

        ImageSingletons.add(SubstrateLoweringProviderFactory.class, SubstrateLLVMLoweringProvider::new);

        ImageSingletons.add(NativeImageCodeCacheFactory.class, new NativeImageCodeCacheFactory() {
            @Override
            public NativeImageCodeCache newCodeCache(CompileQueue compileQueue, NativeImageHeap heap, Platform platform) {
                return new LLVMNativeImageCodeCache(compileQueue.getCompilations(), heap, platform);
            }
        });

        ImageSingletons.add(SnippetRuntime.ExceptionUnwind.class, new SnippetRuntime.ExceptionUnwind() {
            @Override
            public void unwindException(Pointer callerSP) {
                LLVMPersonalityFunction.raiseException();
            }
        });

        ImageSingletons.add(TargetGraphBuilderPlugins.class, new LLVMGraphBuilderPlugins());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        FeatureImpl.BeforeAnalysisAccessImpl accessImpl = (FeatureImpl.BeforeAnalysisAccessImpl) access;
        try {
            accessImpl.registerAsCompiled(LLVMPersonalityFunction.class.getMethod("retrieveException"));
        } catch (NoSuchMethodException e) {
            throw shouldNotReachHere();
        }
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        FeatureImpl.BeforeCompilationAccessImpl accessImpl = (FeatureImpl.BeforeCompilationAccessImpl) access;
        personalityStub = accessImpl.getUniverse().lookup(LLVMPersonalityFunction.getPersonalityStub());
        try {
            retrieveExceptionMethod = accessImpl.getMetaAccess().lookupJavaMethod(LLVMPersonalityFunction.class.getMethod("retrieveException"));
        } catch (NoSuchMethodException e) {
            throw shouldNotReachHere();
        }
    }

    @Override
    public void registerLowerings(RuntimeConfiguration runtimeConfig, OptionValues options, Iterable<DebugHandlersFactory> factories, Providers providers, SnippetReflectionProvider snippetReflection,
                    Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings, boolean hosted) {
        lowerings.put(LoadExceptionObjectNode.class, new LLVMLoadExceptionObjectLowering());
    }

    private static class LLVMLoadExceptionObjectLowering implements NodeLoweringProvider<LoadExceptionObjectNode> {

        @Override
        public void lower(LoadExceptionObjectNode node, LoweringTool tool) {
            FrameState exceptionState = node.stateAfter();
            assert exceptionState != null;

            StructuredGraph graph = node.graph();
            FixedWithNextNode readRegNode = graph.add(new ReadExceptionObjectNode(StampFactory.objectNonNull()));
            graph.replaceFixedWithFixed(node, readRegNode);

            /*
             * When libunwind has found an exception handler, it jumps directly to it from native
             * code. We therefore need the CFunctionEpilogueNode to restore the Java state before we
             * handle the exception.
             */
            CFunctionEpilogueNode cFunctionEpilogueNode = new CFunctionEpilogueNode();
            graph.add(cFunctionEpilogueNode);
            graph.addAfterFixed(readRegNode, cFunctionEpilogueNode);
            cFunctionEpilogueNode.lower(tool);

            graph.addAfterFixed(readRegNode, graph.add(new ExceptionStateNode(exceptionState)));
        }
    }

    private static final int MIN_LLVM_MAJOR_VERSION = 8;
    private static final int MIN_LLVM_MINOR_VERSION = 0;

    private static void checkLLVMVersion() {
        String version = getLLVMVersion();

        String[] splitVersion = version.split("\\.");
        assert splitVersion.length == 3;
        int majorVersion = Integer.parseInt(splitVersion[0]);
        int minorVersion = Integer.parseInt(splitVersion[1]);

        if (majorVersion < MIN_LLVM_MAJOR_VERSION || (majorVersion == MIN_LLVM_MAJOR_VERSION && minorVersion < MIN_LLVM_MINOR_VERSION)) {
            throw UserError.abort("Unsupported LLVM version: " + version + ". Supported versions are LLVM " + MIN_LLVM_MAJOR_VERSION + "." + MIN_LLVM_MINOR_VERSION + ".0 and above");
        }
    }

    private static String getLLVMVersion() {
        int status;
        String output = null;
        try (OutputStream os = new ByteArrayOutputStream()) {
            List<String> cmd = new ArrayList<>();
            cmd.add("llvm-config");
            cmd.add("--version");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            FileUtils.drainInputStream(p.getInputStream(), os);

            status = p.waitFor();
            output = os.toString().trim();
        } catch (IOException | InterruptedException e) {
            status = -1;
        }

        if (status != 0) {
            throw UserError.abort("Using the LLVM backend requires LLVM to be installed on your machine.");
        }

        return output;
    }
}

@AutomaticFeature
@Platforms(Platform.AMD64.class)
class LLVMAMD64TargetSpecificFeature implements Feature {
    private static final int AMD64_RSP_IDX = 7;
    private static final int AMD64_RBP_IDX = 6;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CompilerBackend.getValue().equals("llvm");
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(TargetSpecific.class, new TargetSpecific() {
            @Override
            public String getRegisterInlineAsm(String register) {
                return "movq %" + register + ", $0";
            }

            @Override
            public String getJumpInlineAsm() {
                return "jmpq *$0";
            }

            @Override
            public String getLLVMArchName() {
                return "x86-64";
            }

            /*
             * The return address is pushed to the stack just before each call, but is not part of
             * the stack frame of the callee. It is therefore not accounted for in either call
             * frame.
             */
            @Override
            public int getCallFrameSeparation() {
                return FrameAccess.returnAddressSize();
            }

            /*
             * The frame pointer is stored as the first element on the stack, just below the return
             * address.
             */
            @Override
            public int getFramePointerOffset() {
                return -FrameAccess.wordSize();
            }

            @Override
            public int getStackPointerDwarfRegNum() {
                return AMD64_RSP_IDX;
            }

            @Override
            public int getFramePointerDwarfRegNum() {
                return AMD64_RBP_IDX;
            }

            @Override
            public List<String> getLLCAdditionalOptions() {
                return Collections.singletonList("-no-x86-call-frame-opt");
            }
        });
    }
}

@AutomaticFeature
@Platforms(Platform.AArch64.class)
class LLVMAArch64TargetSpecificFeature implements Feature {
    private static final int AARCH64_FP_IDX = 29;
    private static final int AARCH64_SP_IDX = 31;

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CompilerBackend.getValue().equals("llvm");
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(TargetSpecific.class, new TargetSpecific() {
            @Override
            public String getRegisterInlineAsm(String register) {
                return "MOV $0, " + getLLVMRegisterName(register);
            }

            @Override
            public String getJumpInlineAsm() {
                return "BR $0";
            }

            @Override
            public String getLLVMArchName() {
                return "aarch64";
            }

            /*
             * The return address is not saved on the stack on ARM, so the stack frames have no
             * space inbetween them.
             */
            @Override
            public int getCallFrameSeparation() {
                return 0;
            }

            /*
             * The frame pointer is stored below the saved value for the link register.
             */
            @Override
            public int getFramePointerOffset() {
                return -2 * FrameAccess.wordSize();
            }

            @Override
            public int getStackPointerDwarfRegNum() {
                return AARCH64_SP_IDX;
            }

            @Override
            public int getFramePointerDwarfRegNum() {
                return AARCH64_FP_IDX;
            }

            @Override
            public List<String> getLLCAdditionalOptions() {
                return Collections.singletonList("--frame-pointer=all");
            }

            @Override
            public String getLLVMRegisterName(String register) {
                return register.replace("r", "x");
            }
        });
    }
}
