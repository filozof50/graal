/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.reflect.hosted;

// Checkstyle: allow reflection

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.impl.RuntimeReflectionSupport;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.configure.ConditionalElement;
import com.oracle.svm.core.configure.ConfigurationFile;
import com.oracle.svm.core.configure.ConfigurationFiles;
import com.oracle.svm.core.configure.ReflectionConfigurationParser;
import com.oracle.svm.core.graal.GraalFeature;
import com.oracle.svm.core.reflect.ReflectionAccessorHolder;
import com.oracle.svm.core.reflect.RuntimeReflectionConstructors;
import com.oracle.svm.core.reflect.SubstrateConstructorAccessor;
import com.oracle.svm.core.reflect.SubstrateMethodAccessor;
import com.oracle.svm.core.reflect.SubstrateReflectionAccessorFactory;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FallbackFeature;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.FeatureImpl.DuringSetupAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.FeatureAccessImpl;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.config.ConfigurationParserUtils;
import com.oracle.svm.hosted.meta.MethodPointer;
import com.oracle.svm.hosted.snippets.ReflectionPlugins;
import com.oracle.svm.hosted.substitute.AnnotationSubstitutionProcessor;
import com.oracle.svm.reflect.target.RuntimeReflectionConstructorsImpl;
import com.oracle.svm.util.ModuleSupport;
import com.oracle.svm.util.ReflectionUtil;

import jdk.vm.ci.meta.ResolvedJavaMethod;

@AutomaticFeature
public class ReflectionFeature implements GraalFeature {

    private AnnotationSubstitutionProcessor annotationSubstitutions;

    private ReflectionDataBuilder reflectionData;
    private ImageClassLoader loader;
    private AnalysisUniverse aUniverse;
    private int loadedConfigurations;

    final Map<Executable, Object> accessors = new ConcurrentHashMap<>();

    private static final Method invokePrototype = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "invokePrototype", Object.class, Object[].class);
    private static final Method newInstancePrototype = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "newInstancePrototype", Object[].class);
    private static final Method invokeSpecialErrorMethod = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "invokeSpecialError", Object.class, Object[].class);
    private static final Method newInstanceErrorMethod = ReflectionUtil.lookupMethod(ReflectionAccessorHolder.class, "newInstanceError", Object[].class);

    FeatureImpl.BeforeAnalysisAccessImpl analysisAccess;

    Object getOrCreateAccessor(Executable member) {
        Object existing = accessors.get(member);
        if (existing != null) {
            return existing;
        }

        if (analysisAccess == null) {
            throw VMError.shouldNotReachHere("New Method or Constructor found as reachable after static analysis: " + member);
        }
        return accessors.computeIfAbsent(member, m -> createAccessor(m));
    }

    /**
     * Creates the accessor instances for {@link SubstrateMethodAccessor invoking a method } or
     * {@link SubstrateConstructorAccessor allocating a new instance} using reflection. The accessor
     * instances use function pointer calls to invocation stubs. The invocation stubs unpack the
     * Object[] array arguments and invoke the actual method.
     * 
     * The stubs are methods with manually created Graal IR: {@link ReflectiveInvokeMethod} and
     * {@link ReflectiveNewInstanceMethod}. Since they are only invoked via function pointers and
     * never at a normal call site, they need to be registered for compilation manually. From the
     * point of view of the static analysis, they are root methods.
     * 
     * {@link ConcurrentHashMap#computeIfAbsent} guarantees that this method is called only once per
     * member, so no further synchronization is necessary.
     */
    private Object createAccessor(Executable member) {
        String name = SubstrateUtil.uniqueShortName(member);
        if (member instanceof Method) {
            ResolvedJavaMethod prototype = analysisAccess.getMetaAccess().lookupJavaMethod(invokePrototype).getWrapped();
            ResolvedJavaMethod invokeMethod = createReflectiveInvokeMethod(name, prototype, member, false);

            ResolvedJavaMethod invokeSpecialMethod;
            AnalysisMethod targetMethod = analysisAccess.getMetaAccess().lookupJavaMethod(member);
            if (targetMethod.isStatic() || targetMethod.isAbstract()) {
                /*
                 * Static / abstract methods cannot be invoked using an invokeSpecial. Such direct
                 * calls (instead of virtual calls) are not available via the Java reflection API,
                 * but used by our method handle implementation.
                 */
                invokeSpecialMethod = analysisAccess.getMetaAccess().lookupJavaMethod(invokeSpecialErrorMethod);
            } else if (targetMethod.canBeStaticallyBound()) {
                /*
                 * Methods that can be statically bound are always de-virtualized to a special
                 * invoke, so no separate invocation stub is necessary.
                 */
                invokeSpecialMethod = invokeMethod;
            } else {
                invokeSpecialMethod = createReflectiveInvokeMethod("invokeSpecial_" + name, prototype, member, true);
            }
            return ImageSingletons.lookup(SubstrateReflectionAccessorFactory.class).createMethodAccessor(member, register(invokeMethod), register(invokeSpecialMethod));

        } else {
            ResolvedJavaMethod newInstanceMethod;
            Class<?> holder = member.getDeclaringClass();
            if (Modifier.isAbstract(holder.getModifiers()) || holder.isInterface() || holder.isPrimitive() || holder.isArray()) {
                /*
                 * Invoking the constructor of an abstract class always throws an
                 * InstantiationException. It should not be possible to get a Constructor object for
                 * an interface, array, or primitive type, but we are defensive and throw the
                 * exception in that case too.
                 */
                newInstanceMethod = analysisAccess.getMetaAccess().lookupJavaMethod(newInstanceErrorMethod);
            } else {
                ResolvedJavaMethod prototype = analysisAccess.getMetaAccess().lookupJavaMethod(newInstancePrototype).getWrapped();
                newInstanceMethod = createReflectiveNewInstanceMethod(name, prototype, (Constructor<?>) member);
            }
            return ImageSingletons.lookup(SubstrateReflectionAccessorFactory.class).createConstructorAccessor(member, register(newInstanceMethod));
        }
    }

    private CFunctionPointer register(ResolvedJavaMethod method) {
        AnalysisMethod aMethod = method instanceof AnalysisMethod ? (AnalysisMethod) method : analysisAccess.getUniverse().lookup(method);
        analysisAccess.registerAsCompiled(aMethod);
        return MethodPointer.factory(aMethod);
    }

    protected ResolvedJavaMethod createReflectiveInvokeMethod(String name, ResolvedJavaMethod prototype, Executable member, boolean specialInvoke) {
        return new ReflectiveInvokeMethod(name, prototype, (Method) member, specialInvoke);
    }

    protected ResolvedJavaMethod createReflectiveNewInstanceMethod(String name, ResolvedJavaMethod prototype, Constructor<?> constructor) {
        return new ReflectiveNewInstanceMethod(name, prototype, constructor);
    }

    protected void inspectAccessibleField(@SuppressWarnings("unused") Field field) {
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.reflect", false);

        reflectionData = new ReflectionDataBuilder((FeatureAccessImpl) access);
        ImageSingletons.add(RuntimeReflectionSupport.class, reflectionData);
        ImageSingletons.add(RuntimeReflectionConstructors.class, new RuntimeReflectionConstructorsImpl());
    }

    @Override
    public void duringSetup(DuringSetupAccess a) {
        DuringSetupAccessImpl access = (DuringSetupAccessImpl) a;
        aUniverse = access.getUniverse();

        access.registerObjectReplacer(new ReflectionObjectReplacer(access.getMetaAccess()));

        ReflectionConfigurationParser<ConditionalElement<Class<?>>> parser = ConfigurationParserUtils.create(reflectionData, access.getImageClassLoader());
        loadedConfigurations = ConfigurationParserUtils.parseAndRegisterConfigurations(parser, access.getImageClassLoader(), "reflection",
                        ConfigurationFiles.Options.ReflectionConfigurationFiles, ConfigurationFiles.Options.ReflectionConfigurationResources,
                        ConfigurationFile.REFLECTION.getFileName());

        loader = access.getImageClassLoader();
        annotationSubstitutions = ((Inflation) access.getBigBang()).getAnnotationSubstitutionProcessor();
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        analysisAccess = (FeatureImpl.BeforeAnalysisAccessImpl) access;
        /* duplicated to reduce the number of analysis iterations */
        reflectionData.flushConditionalConfiguration(access);
    }

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        reflectionData.flushConditionalConfiguration(access);
        reflectionData.duringAnalysis(access);
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        analysisAccess = null;
        reflectionData.afterAnalysis();
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        if (!ImageSingletons.contains(FallbackFeature.class)) {
            return;
        }
        FallbackFeature.FallbackImageRequest reflectionFallback = ImageSingletons.lookup(FallbackFeature.class).reflectionFallback;
        if (reflectionFallback != null && loadedConfigurations == 0) {
            throw reflectionFallback;
        }
    }

    @Override
    public void registerInvocationPlugins(Providers providers, SnippetReflectionProvider snippetReflection, Plugins plugins, ParsingReason reason) {
        ReflectionPlugins.registerInvocationPlugins(loader, snippetReflection, annotationSubstitutions,
                        plugins.getClassInitializationPlugin(), plugins.getInvocationPlugins(), aUniverse, reason);
    }
}
