/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.builder.core;

import static com.android.SdkConstants.DOT_CLASS;
import static com.android.SdkConstants.DOT_DEX;
import static com.android.SdkConstants.DOT_XML;
import static com.android.SdkConstants.FD_RES_XML;
import static com.android.builder.core.BuilderConstants.ANDROID_WEAR;
import static com.android.builder.core.BuilderConstants.ANDROID_WEAR_MICRO_APK;
import static com.android.manifmerger.ManifestMerger2.Invoker;
import static com.android.manifmerger.ManifestMerger2.SystemProperty;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.builder.compiling.DependencyFileProcessor;
import com.android.builder.core.BuildToolsServiceLoader.BuildToolServiceLoader;
import com.android.builder.dependency.ManifestDependency;
import com.android.builder.dependency.SymbolFileProvider;
import com.android.builder.internal.ClassFieldImpl;
import com.android.builder.internal.SymbolLoader;
import com.android.builder.internal.SymbolWriter;
import com.android.builder.internal.TestManifestGenerator;
import com.android.builder.internal.compiler.AidlProcessor;
import com.android.builder.internal.compiler.DexWrapper;
import com.android.builder.internal.compiler.JackConversionCache;
import com.android.builder.internal.compiler.LeafFolderGatherer;
import com.android.builder.internal.compiler.PreDexCache;
import com.android.builder.internal.compiler.RenderScriptProcessor;
import com.android.builder.internal.compiler.SourceSearcher;
import com.android.builder.internal.incremental.DependencyData;
import com.android.builder.internal.packaging.Packager;
import com.android.builder.model.ClassField;
import com.android.builder.model.SigningConfig;
import com.android.builder.model.SyncIssue;
import com.android.builder.packaging.DuplicateFileException;
import com.android.builder.packaging.PackagerException;
import com.android.builder.packaging.SealedPackageException;
import com.android.builder.packaging.SigningException;
import com.android.builder.sdk.SdkInfo;
import com.android.builder.sdk.TargetInfo;
import com.android.builder.signing.SignedJarBuilder;
import com.android.ide.common.internal.AaptCruncher;
import com.android.ide.common.internal.LoggedErrorException;
import com.android.ide.common.internal.PngCruncher;
import com.android.ide.common.process.CachedProcessOutputHandler;
import com.android.ide.common.process.JavaProcessExecutor;
import com.android.ide.common.process.JavaProcessInfo;
import com.android.ide.common.process.ProcessException;
import com.android.ide.common.process.ProcessExecutor;
import com.android.ide.common.process.ProcessInfo;
import com.android.ide.common.process.ProcessInfoBuilder;
import com.android.ide.common.process.ProcessOutputHandler;
import com.android.ide.common.process.ProcessResult;
import com.android.ide.common.signing.CertificateInfo;
import com.android.ide.common.signing.KeystoreHelper;
import com.android.ide.common.signing.KeytoolException;
import com.android.ide.common.xml.XmlPrettyPrinter;
import com.android.jack.api.ConfigNotSupportedException;
import com.android.jack.api.JackProvider;
import com.android.jack.api.v01.Api01CompilationTask;
import com.android.jack.api.v01.Api01Config;
import com.android.jack.api.v01.CompilationException;
import com.android.jack.api.v01.ConfigurationException;
import com.android.jack.api.v01.MultiDexKind;
import com.android.jack.api.v01.ReporterKind;
import com.android.jack.api.v01.UnrecoverableException;
import com.android.jill.api.JillProvider;
import com.android.jill.api.v01.Api01TranslationTask;
import com.android.jill.api.v01.TranslationException;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.PlaceholderHandler;
import com.android.repository.Revision;
import com.android.sdklib.BuildToolInfo;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.IAndroidTarget.OptionalLibrary;
import com.android.utils.FileUtils;
import com.android.utils.ILogger;
import com.android.utils.Pair;
import com.android.utils.SdkUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This is the main builder class. It is given all the data to process the build (such as
 * {@link DefaultProductFlavor}s, {@link DefaultBuildType} and dependencies) and use them when doing specific
 * build steps.
 *
 * To use:
 * create a builder with {@link #AndroidBuilder(String, String, ProcessExecutor, JavaProcessExecutor, ErrorReporter, ILogger, boolean)}
 *
 * then build steps can be done with
 * {@link #mergeManifests(File, List, List, String, int, String, String, String, Integer, String, String, ManifestMerger2.MergeType, Map, File)}
 * {@link #processTestManifest(String, String, String, String, String, Boolean, Boolean, File, List, Map, File, File)}
 * {@link #processResources(AaptPackageProcessBuilder, boolean, ProcessOutputHandler)}
 * {@link #compileAllAidlFiles(List, File, File, Collection, List, DependencyFileProcessor, ProcessOutputHandler)}
 * {@link #convertByteCode(Collection, File, boolean, File, DexOptions, List, boolean, boolean, ProcessOutputHandler)}
 * {@link #packageApk(String, Set, Collection, Collection, Set, boolean, SigningConfig, String)}
 *
 * Java compilation is not handled but the builder provides the bootclasspath with
 * {@link #getBootClasspath(boolean)}.
 */
public class AndroidBuilder {

    private static final Revision MIN_BUILD_TOOLS_REV = new Revision(19, 1, 0);

    private static final DependencyFileProcessor sNoOpDependencyFileProcessor = new DependencyFileProcessor() {
        @Override
        public DependencyData processFile(@NonNull File dependencyFile) {
            return null;
        }
    };

    @NonNull
    private final String mProjectId;
    @NonNull
    private final ILogger mLogger;

    @NonNull
    private final ProcessExecutor mProcessExecutor;
    @NonNull
    private final JavaProcessExecutor mJavaProcessExecutor;
    @NonNull
    private final ErrorReporter mErrorReporter;

    private final boolean mVerboseExec;

    @Nullable private String mCreatedBy;


    private SdkInfo mSdkInfo;
    private TargetInfo mTargetInfo;

    private List<File> mBootClasspathFiltered;
    private List<File> mBootClasspathAll;
    @NonNull
    private List<LibraryRequest> mLibraryRequests = ImmutableList.of();

    /**
     * Creates an AndroidBuilder.
     * <p/>
     * <var>verboseExec</var> is needed on top of the ILogger due to remote exec tools not being
     * able to output info and verbose messages separately.
     *
     * @param createdBy the createdBy String for the apk manifest.
     * @param logger the Logger
     * @param verboseExec whether external tools are launched in verbose mode
     */
    public AndroidBuilder(
            @NonNull String projectId,
            @Nullable String createdBy,
            @NonNull ProcessExecutor processExecutor,
            @NonNull JavaProcessExecutor javaProcessExecutor,
            @NonNull ErrorReporter errorReporter,
            @NonNull ILogger logger,
            boolean verboseExec) {
        mProjectId = checkNotNull(projectId);
        mCreatedBy = createdBy;
        mProcessExecutor = checkNotNull(processExecutor);
        mJavaProcessExecutor = checkNotNull(javaProcessExecutor);
        mErrorReporter = checkNotNull(errorReporter);
        mLogger = checkNotNull(logger);
        mVerboseExec = verboseExec;
    }

    /**
     * Sets the SdkInfo and the targetInfo on the builder. This is required to actually
     * build (some of the steps).
     *
     * @param sdkInfo the SdkInfo
     * @param targetInfo the TargetInfo
     *
     * @see com.android.builder.sdk.SdkLoader
     */
    public void setTargetInfo(
            @NonNull SdkInfo sdkInfo,
            @NonNull TargetInfo targetInfo,
            @NonNull Collection<LibraryRequest> libraryRequests) {
        mSdkInfo = sdkInfo;
        mTargetInfo = targetInfo;

        if (mTargetInfo.getBuildTools().getRevision().compareTo(MIN_BUILD_TOOLS_REV) < 0) {
            throw new IllegalArgumentException(String.format(
                    "The SDK Build Tools revision (%1$s) is too low for project '%2$s'. Minimum required is %3$s",
                    mTargetInfo.getBuildTools().getRevision(), mProjectId, MIN_BUILD_TOOLS_REV));
        }

        mLibraryRequests = ImmutableList.copyOf(libraryRequests);
    }

    /**
     * Returns the SdkInfo, if set.
     */
    @Nullable
    public SdkInfo getSdkInfo() {
        return mSdkInfo;
    }

    /**
     * Returns the TargetInfo, if set.
     */
    @Nullable
    public TargetInfo getTargetInfo() {
        return mTargetInfo;
    }

    @NonNull
    public ILogger getLogger() {
        return mLogger;
    }

    @NonNull
    public ErrorReporter getErrorReporter() {
        return mErrorReporter;
    }

    /**
     * Returns the compilation target, if set.
     */
    @Nullable
    public IAndroidTarget getTarget() {
        checkState(mTargetInfo != null,
                "Cannot call getTarget() before setTargetInfo() is called.");
        return mTargetInfo.getTarget();
    }

    /**
     * Returns whether the compilation target is a preview.
     */
    public boolean isPreviewTarget() {
        checkState(mTargetInfo != null,
                "Cannot call isTargetAPreview() before setTargetInfo() is called.");
        return mTargetInfo.getTarget().getVersion().isPreview();
    }

    public String getTargetCodename() {
        checkState(mTargetInfo != null,
                "Cannot call getTargetCodename() before setTargetInfo() is called.");
        return mTargetInfo.getTarget().getVersion().getCodename();
    }

    @NonNull
    public File getDxJar() {
        checkState(mTargetInfo != null,
                "Cannot call getDxJar() before setTargetInfo() is called.");
        return new File(mTargetInfo.getBuildTools().getPath(BuildToolInfo.PathId.DX_JAR));
    }

    /**
     * Helper method to get the boot classpath to be used during compilation.
     *
     * @param includeOptionalLibraries if true, optional libraries are included even if not
     *                                 required by the project setup.
     */
    @NonNull
    public List<File> getBootClasspath(boolean includeOptionalLibraries) {
        if (includeOptionalLibraries) {
            return computeFullBootClasspath();
        }

        return computeFilteredBootClasspath();
    }

    private List<File> computeFilteredBootClasspath() {
        // computes and caches the filtered boot classpath.
        // Changes here should be applied to #computeFullClasspath()

        if (mBootClasspathFiltered == null) {
            checkState(mTargetInfo != null,
                    "Cannot call getBootClasspath() before setTargetInfo() is called.");
            List<File> classpath = Lists.newArrayList();
            IAndroidTarget target = mTargetInfo.getTarget();

            for (String p : target.getBootClasspath()) {
                classpath.add(new File(p));
            }

            List<LibraryRequest> requestedLibs = Lists.newArrayList(mLibraryRequests);

            // add additional libraries if any
            List<OptionalLibrary> libs = target.getAdditionalLibraries();
            for (OptionalLibrary lib : libs) {
                // add it always for now
                classpath.add(lib.getJar());

                // remove from list of requested if match
                LibraryRequest requestedLib = findMatchingLib(lib.getName(), requestedLibs);
                if (requestedLib != null) {
                    requestedLibs.remove(requestedLib);
                }
            }

            // add optional libraries if needed.
            List<OptionalLibrary> optionalLibraries = target.getOptionalLibraries();
            for (OptionalLibrary lib : optionalLibraries) {
                // search if requested
                LibraryRequest requestedLib = findMatchingLib(lib.getName(), requestedLibs);
                if (requestedLib != null) {
                    // add to classpath
                    classpath.add(lib.getJar());

                    // remove from requested list.
                    requestedLibs.remove(requestedLib);
                }
            }

            // look for not found requested libraries.
            for (LibraryRequest library : requestedLibs) {
                mErrorReporter.handleSyncError(
                        library.getName(),
                        SyncIssue.TYPE_OPTIONAL_LIB_NOT_FOUND,
                        "Unable to find optional library: " + library.getName());
            }

            // add annotations.jar if needed.
            if (target.getVersion().getApiLevel() <= 15) {
                classpath.add(mSdkInfo.getAnnotationsJar());
            }

            mBootClasspathFiltered = ImmutableList.copyOf(classpath);
        }

        return mBootClasspathFiltered;
    }

    private List<File> computeFullBootClasspath() {
        // computes and caches the full boot classpath.
        // Changes here should be applied to #computeFilteredClasspath()

        if (mBootClasspathAll == null) {
            checkState(mTargetInfo != null,
                    "Cannot call getBootClasspath() before setTargetInfo() is called.");

            List<File> classpath = Lists.newArrayList();

            IAndroidTarget target = mTargetInfo.getTarget();

            for (String p : target.getBootClasspath()) {
                classpath.add(new File(p));
            }

            // add additional libraries if any
            List<OptionalLibrary> libs = target.getAdditionalLibraries();
            for (OptionalLibrary lib : libs) {
                classpath.add(lib.getJar());
            }

            // add optional libraries if any
            List<OptionalLibrary> optionalLibraries = target.getOptionalLibraries();
            for (OptionalLibrary lib : optionalLibraries) {
                classpath.add(lib.getJar());
            }

            // add annotations.jar if needed.
            if (target.getVersion().getApiLevel() <= 15) {
                classpath.add(mSdkInfo.getAnnotationsJar());
            }

            mBootClasspathAll = ImmutableList.copyOf(classpath);
        }

        return mBootClasspathAll;
    }

    @Nullable
    private static LibraryRequest findMatchingLib(@NonNull String name, @NonNull List<LibraryRequest> libraries) {
        for (LibraryRequest library : libraries) {
            if (name.equals(library.getName())) {
                return library;
            }
        }

        return null;
    }

    /**
     * Helper method to get the boot classpath to be used during compilation.
     *
     * @param includeOptionalLibraries if true, optional libraries are included even if not
     *                                 required by the project setup.
     */
    @NonNull
    public List<String> getBootClasspathAsStrings(boolean includeOptionalLibraries) {
        List<File> classpath = getBootClasspath(includeOptionalLibraries);

        // convert to Strings.
        List<String> results = Lists.newArrayListWithCapacity(classpath.size());
        for (File f : classpath) {
            results.add(f.getAbsolutePath());
        }

        return results;
    }

    /**
     * Returns the jar file for the renderscript mode.
     *
     * This may return null if the SDK has not been loaded yet.
     *
     * @return the jar file, or null.
     *
     * @see #setTargetInfo(SdkInfo, TargetInfo, Collection)
     */
    @Nullable
    public File getRenderScriptSupportJar() {
        if (mTargetInfo != null) {
            return RenderScriptProcessor.getSupportJar(
                    mTargetInfo.getBuildTools().getLocation().getAbsolutePath());
        }

        return null;
    }

    /**
     * Returns the compile classpath for this config. If the config tests a library, this
     * will include the classpath of the tested config.
     *
     * If the SDK was loaded, this may include the renderscript support jar.
     *
     * @return a non null, but possibly empty set.
     */
    @NonNull
    public Set<File> getCompileClasspath(@NonNull VariantConfiguration<?,?,?> variantConfiguration) {
        Set<File> compileClasspath = variantConfiguration.getCompileClasspath();

        if (variantConfiguration.getRenderscriptSupportModeEnabled()) {
            File renderScriptSupportJar = getRenderScriptSupportJar();

            Set<File> fullJars = Sets.newHashSetWithExpectedSize(compileClasspath.size() + 1);
            fullJars.addAll(compileClasspath);
            if (renderScriptSupportJar != null) {
                fullJars.add(renderScriptSupportJar);
            }
            compileClasspath = fullJars;
        }

        return compileClasspath;
    }

    /**
     * Returns the list of packaged jars for this config. If the config tests a library, this
     * will include the jars of the tested config
     *
     * If the SDK was loaded, this may include the renderscript support jar.
     *
     * @return a non null, but possibly empty list.
     */
    @NonNull
    public Set<File> getAllPackagedJars(@NonNull VariantConfiguration<?,?,?> variantConfiguration) {
        Set<File> packagedJars = Sets.newHashSet(variantConfiguration.getAllPackagedJars());

        if (variantConfiguration.getRenderscriptSupportModeEnabled()) {
            File renderScriptSupportJar = getRenderScriptSupportJar();

            if (renderScriptSupportJar != null) {
                packagedJars.add(renderScriptSupportJar);
            }
        }

        return packagedJars;
    }

    /**
     * Returns the list of packaged jars for this config. If the config tests a library, this
     * will include the jars of the tested config
     *
     * If the SDK was loaded, this may include the renderscript support jar.
     *
     * @return a non null, but possibly empty list.
     */
    @NonNull
    public Set<File> getAdditionalPackagedJars(@NonNull VariantConfiguration<?,?,?> variantConfiguration) {

        if (variantConfiguration.getRenderscriptSupportModeEnabled()) {
            File renderScriptSupportJar = getRenderScriptSupportJar();

            if (renderScriptSupportJar != null) {
                return ImmutableSet.of(renderScriptSupportJar);
            }
        }

        return ImmutableSet.of();
    }

    /**
     * Returns the native lib folder for the renderscript mode.
     *
     * This may return null if the SDK has not been loaded yet.
     *
     * @return the folder, or null.
     *
     * @see #setTargetInfo(SdkInfo, TargetInfo, Collection)
     */
    @Nullable
    public File getSupportNativeLibFolder() {
        if (mTargetInfo != null) {
            return RenderScriptProcessor.getSupportNativeLibFolder(
                    mTargetInfo.getBuildTools().getLocation().getAbsolutePath());
        }

        return null;
    }

    /**
     * Returns an {@link PngCruncher} using aapt underneath
     * @return an PngCruncher object
     */
    @NonNull
    public PngCruncher getAaptCruncher(ProcessOutputHandler processOutputHandler) {
        checkState(mTargetInfo != null,
                "Cannot call getAaptCruncher() before setTargetInfo() is called.");
        return new AaptCruncher(
                mTargetInfo.getBuildTools().getPath(BuildToolInfo.PathId.AAPT),
                mProcessExecutor,
                processOutputHandler);
    }

    @NonNull
    public ProcessExecutor getProcessExecutor() {
        return mProcessExecutor;
    }

    @NonNull
    public ProcessResult executeProcess(@NonNull ProcessInfo processInfo,
            @NonNull ProcessOutputHandler handler) {
        return mProcessExecutor.execute(processInfo, handler);
    }

    @NonNull
    public static ClassField createClassField(@NonNull String type, @NonNull String name, @NonNull String value) {
        return new ClassFieldImpl(type, name, value);
    }

    // Temporary trampoline
    public static String formatXml(@NonNull org.w3c.dom.Node node, boolean endWithNewline) {
        return XmlPrettyPrinter.prettyPrint(node, endWithNewline);
    }

    /**
     * Invoke the Manifest Merger version 2.
     */
    public void mergeManifests(
            @NonNull File mainManifest,
            @NonNull List<File> manifestOverlays,
            @NonNull List<? extends ManifestDependency> libraries,
            String packageOverride,
            int versionCode,
            String versionName,
            @Nullable String minSdkVersion,
            @Nullable String targetSdkVersion,
            @Nullable Integer maxSdkVersion,
            @NonNull String outManifestLocation,
            @Nullable String outAaptSafeManifestLocation,
            @Nullable String outInstantRunManifestLocation,
            ManifestMerger2.MergeType mergeType,
            Map<String, Object> placeHolders,
            List<Invoker.Feature> optionalFeatures,
            @Nullable File reportFile) {

        try {

            Invoker manifestMergerInvoker =
                    ManifestMerger2.newMerger(mainManifest, mLogger, mergeType)
                    .setPlaceHolderValues(placeHolders)
                    .addFlavorAndBuildTypeManifests(
                            manifestOverlays.toArray(new File[manifestOverlays.size()]))
                    .addLibraryManifests(collectLibraries(libraries))
                    .withFeatures(optionalFeatures.toArray(
                            new Invoker.Feature[optionalFeatures.size()]))
                    .setMergeReportFile(reportFile);

            if (mergeType == ManifestMerger2.MergeType.APPLICATION) {
                manifestMergerInvoker.withFeatures(Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);
            }

            //noinspection VariableNotUsedInsideIf
            if (outAaptSafeManifestLocation != null) {
                manifestMergerInvoker.withFeatures(Invoker.Feature.MAKE_AAPT_SAFE);
            }

            setInjectableValues(manifestMergerInvoker,
                    packageOverride, versionCode, versionName,
                    minSdkVersion, targetSdkVersion, maxSdkVersion);

            MergingReport mergingReport = manifestMergerInvoker.merge();
            mLogger.info("Merging result:" + mergingReport.getResult());
            switch (mergingReport.getResult()) {
                case WARNING:
                    mergingReport.log(mLogger);
                    // fall through since these are just warnings.
                case SUCCESS:
                    String xmlDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.MERGED);
                    String annotatedDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.BLAME);
                    if (annotatedDocument != null) {
                        mLogger.verbose(annotatedDocument);
                    }
                    save(xmlDocument, new File(outManifestLocation));
                    mLogger.info("Merged manifest saved to " + outManifestLocation);

                    if (outAaptSafeManifestLocation != null) {
                        save(mergingReport.getMergedDocument(MergingReport.MergedManifestKind.AAPT_SAFE),
                                new File(outAaptSafeManifestLocation));
                    }

                    if (outInstantRunManifestLocation != null) {
                        String instantRunMergedManifest = mergingReport.getMergedDocument(
                                MergingReport.MergedManifestKind.INSTANT_RUN);
                        if (instantRunMergedManifest != null) {
                            save(instantRunMergedManifest, new File(outInstantRunManifestLocation));
                        }
                    }
                    break;
                case ERROR:
                    mergingReport.log(mLogger);
                    throw new RuntimeException(mergingReport.getReportString());
                default:
                    throw new RuntimeException("Unhandled result type : "
                            + mergingReport.getResult());
            }
        } catch (ManifestMerger2.MergeFailureException e) {
            // TODO: unacceptable.
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the {@link com.android.manifmerger.ManifestMerger2.SystemProperty} that can be injected
     * in the manifest file.
     */
    private static void setInjectableValues(
            ManifestMerger2.Invoker<?> invoker,
            String packageOverride,
            int versionCode,
            String versionName,
            @Nullable String minSdkVersion,
            @Nullable String targetSdkVersion,
            @Nullable Integer maxSdkVersion) {

        if (!Strings.isNullOrEmpty(packageOverride)) {
            invoker.setOverride(SystemProperty.PACKAGE, packageOverride);
        }
        if (versionCode > 0) {
            invoker.setOverride(SystemProperty.VERSION_CODE,
                    String.valueOf(versionCode));
        }
        if (!Strings.isNullOrEmpty(versionName)) {
            invoker.setOverride(SystemProperty.VERSION_NAME, versionName);
        }
        if (!Strings.isNullOrEmpty(minSdkVersion)) {
            invoker.setOverride(SystemProperty.MIN_SDK_VERSION, minSdkVersion);
        }
        if (!Strings.isNullOrEmpty(targetSdkVersion)) {
            invoker.setOverride(SystemProperty.TARGET_SDK_VERSION, targetSdkVersion);
        }
        if (maxSdkVersion != null) {
            invoker.setOverride(SystemProperty.MAX_SDK_VERSION, maxSdkVersion.toString());
        }
    }

    /**
     * Saves the {@link com.android.manifmerger.XmlDocument} to a file in UTF-8 encoding.
     * @param xmlDocument xml document to save.
     * @param out file to save to.
     */
    private static void save(String xmlDocument, File out) {
        try {
            Files.createParentDirs(out);
            Files.write(xmlDocument, out, Charsets.UTF_8);
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Collect the list of libraries' manifest files.
     * @param libraries declared dependencies
     * @return a list of files and names for the libraries' manifest files.
     */
    private static ImmutableList<Pair<String, File>> collectLibraries(
            List<? extends ManifestDependency> libraries) {

        ImmutableList.Builder<Pair<String, File>> manifestFiles = ImmutableList.builder();
        if (libraries != null) {
            collectLibraries(libraries, manifestFiles);
        }
        return manifestFiles.build();
    }

    /**
     * recursively calculate the list of libraries to merge the manifests files from.
     * @param libraries the dependencies
     * @param manifestFiles list of files and names identifiers for the libraries' manifest files.
     */
    private static void collectLibraries(List<? extends ManifestDependency> libraries,
            ImmutableList.Builder<Pair<String, File>> manifestFiles) {

        for (ManifestDependency library : libraries) {
            manifestFiles.add(Pair.of(library.getName(), library.getManifest()));
            List<? extends ManifestDependency> manifestDependencies = library
                    .getManifestDependencies();
            if (!manifestDependencies.isEmpty()) {
                collectLibraries(manifestDependencies, manifestFiles);
            }
        }
    }

    /**
     * Creates the manifest for a test variant
     *
     * @param testApplicationId the application id of the test application
     * @param minSdkVersion the minSdkVersion of the test application
     * @param targetSdkVersion the targetSdkVersion of the test application
     * @param testedApplicationId the application id of the tested application
     * @param instrumentationRunner the name of the instrumentation runner
     * @param handleProfiling whether or not the Instrumentation object will turn profiling on and off
     * @param functionalTest whether or not the Instrumentation class should run as a functional test
     * @param testManifestFile optionally user provided AndroidManifest.xml for testing application
     * @param libraries the library dependency graph
     * @param outManifest the output location for the merged manifest
     *
     * @see VariantConfiguration#getApplicationId()
     * @see VariantConfiguration#getTestedConfig()
     * @see VariantConfiguration#getMinSdkVersion()
     * @see VariantConfiguration#getTestedApplicationId()
     * @see VariantConfiguration#getInstrumentationRunner()
     * @see VariantConfiguration#getHandleProfiling()
     * @see VariantConfiguration#getFunctionalTest()
     * @see VariantConfiguration#getDirectLibraries()
     */
    public void processTestManifest(
            @NonNull String testApplicationId,
            @NonNull String minSdkVersion,
            @NonNull String targetSdkVersion,
            @NonNull String testedApplicationId,
            @NonNull String instrumentationRunner,
            @NonNull Boolean handleProfiling,
            @NonNull Boolean functionalTest,
            @Nullable File testManifestFile,
            @NonNull List<? extends ManifestDependency> libraries,
            @NonNull Map<String, Object> manifestPlaceholders,
            @NonNull File outManifest,
            @NonNull File tmpDir) throws IOException {
        checkNotNull(testApplicationId, "testApplicationId cannot be null.");
        checkNotNull(testedApplicationId, "testedApplicationId cannot be null.");
        checkNotNull(instrumentationRunner, "instrumentationRunner cannot be null.");
        checkNotNull(handleProfiling, "handleProfiling cannot be null.");
        checkNotNull(functionalTest, "functionalTest cannot be null.");
        checkNotNull(libraries, "libraries cannot be null.");
        checkNotNull(outManifest, "outManifestLocation cannot be null.");

        // These temp files are only need in the middle of processing manifests; delete
        // them when they're done. We're not relying on File#deleteOnExit for this
        // since in the Gradle daemon for example that would leave the files around much
        // longer than we want.
        File tempFile1 = null;
        File tempFile2 = null;
        try {
            FileUtils.mkdirs(tmpDir);
            File generatedTestManifest = libraries.isEmpty() && testManifestFile == null
                    ? outManifest
                    : (tempFile1 = File.createTempFile("manifestMerger", ".xml", tmpDir));

            mLogger.verbose("Generating in %1$s", generatedTestManifest.getAbsolutePath());
            generateTestManifest(
                    testApplicationId,
                    minSdkVersion,
                    targetSdkVersion.equals("-1") ? null : targetSdkVersion,
                    testedApplicationId,
                    instrumentationRunner,
                    handleProfiling,
                    functionalTest,
                    generatedTestManifest);

            if (testManifestFile != null) {
                tempFile2 = File.createTempFile("manifestMerger", ".xml", tmpDir);
                mLogger.verbose("Merging user supplied manifest in %1$s",
                        generatedTestManifest.getAbsolutePath());
                Invoker invoker = ManifestMerger2.newMerger(
                        testManifestFile, mLogger, ManifestMerger2.MergeType.APPLICATION)
                        .setOverride(SystemProperty.PACKAGE, testApplicationId)
                        .setPlaceHolderValues(manifestPlaceholders)
                        .setPlaceHolderValue(PlaceholderHandler.INSTRUMENTATION_RUNNER,
                                instrumentationRunner)
                        .addLibraryManifests(generatedTestManifest);

                invoker.setOverride(SystemProperty.MIN_SDK_VERSION, minSdkVersion);

                if (!targetSdkVersion.equals("-1")) {
                    invoker.setOverride(SystemProperty.TARGET_SDK_VERSION, targetSdkVersion);
                }
                MergingReport mergingReport = invoker.merge();
                if (libraries.isEmpty()) {
                    handleMergingResult(mergingReport, outManifest);
                } else {
                    handleMergingResult(mergingReport, tempFile2);
                    generatedTestManifest = tempFile2;
                }
            }

            if (!libraries.isEmpty()) {
                MergingReport mergingReport = ManifestMerger2.newMerger(
                        generatedTestManifest, mLogger, ManifestMerger2.MergeType.APPLICATION)
                        .withFeatures(Invoker.Feature.REMOVE_TOOLS_DECLARATIONS)
                        .setOverride(SystemProperty.PACKAGE, testApplicationId)
                        .addLibraryManifests(collectLibraries(libraries))
                        .setPlaceHolderValues(manifestPlaceholders)
                        .merge();

                handleMergingResult(mergingReport, outManifest);
            }
        } catch(Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (tempFile1 != null) {
                FileUtils.delete(tempFile1);
            }
            if (tempFile2 != null) {
                FileUtils.delete(tempFile2);
            }
        }
    }

    private void handleMergingResult(@NonNull MergingReport mergingReport, @NonNull File outFile) {
        switch (mergingReport.getResult()) {
            case WARNING:
                mergingReport.log(mLogger);
                // fall through since these are just warnings.
            case SUCCESS:
                try {
                    String annotatedDocument = mergingReport.getMergedDocument(
                            MergingReport.MergedManifestKind.BLAME);
                    if (annotatedDocument != null) {
                        mLogger.verbose(annotatedDocument);
                    } else {
                        mLogger.verbose("No blaming records from manifest merger");
                    }
                } catch (Exception e) {
                    mLogger.error(e, "cannot print resulting xml");
                }
                String finalMergedDocument = mergingReport
                        .getMergedDocument(MergingReport.MergedManifestKind.MERGED);
                if (finalMergedDocument == null) {
                    throw new RuntimeException("No result from manifest merger");
                }
                try {
                    Files.write(finalMergedDocument, outFile, Charsets.UTF_8);
                } catch (IOException e) {
                    mLogger.error(e, "Cannot write resulting xml");
                    throw new RuntimeException(e);
                }
                mLogger.info("Merged manifest saved to " + outFile);
                break;
            case ERROR:
                mergingReport.log(mLogger);
                throw new RuntimeException(mergingReport.getReportString());
            default:
                throw new RuntimeException("Unhandled result type : "
                        + mergingReport.getResult());
        }
    }

    private static void generateTestManifest(
            @NonNull String testApplicationId,
            @Nullable String minSdkVersion,
            @Nullable String targetSdkVersion,
            @NonNull String testedApplicationId,
            @NonNull String instrumentationRunner,
            @NonNull Boolean handleProfiling,
            @NonNull Boolean functionalTest,
            @NonNull File outManifestLocation) {
        TestManifestGenerator generator = new TestManifestGenerator(
                outManifestLocation,
                testApplicationId,
                minSdkVersion,
                targetSdkVersion,
                testedApplicationId,
                instrumentationRunner,
                handleProfiling,
                functionalTest);
        try {
            generator.generate();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Process the resources and generate R.java and/or the packaged resources.
     *
     *  @param aaptCommand aapt command invocation parameters.
     *  @param enforceUniquePackageName if true method will fail if some libraries share the same
     *                                 package name
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws ProcessException
     */
    public void processResources(
            @NonNull AaptPackageProcessBuilder aaptCommand,
            boolean enforceUniquePackageName,
            @NonNull ProcessOutputHandler processOutputHandler)
            throws IOException, InterruptedException, ProcessException {

        checkState(mTargetInfo != null,
                "Cannot call processResources() before setTargetInfo() is called.");

        // launch aapt: create the command line
        ProcessInfo processInfo = aaptCommand.build(
                mTargetInfo.getBuildTools(), mTargetInfo.getTarget(), mLogger);

        ProcessResult result = mProcessExecutor.execute(processInfo, processOutputHandler);
        result.rethrowFailure().assertNormalExitValue();

        // If the project has libraries, R needs to be created for each library.
        if (aaptCommand.getSourceOutputDir() != null
                && !aaptCommand.getLibraries().isEmpty()) {
            SymbolLoader fullSymbolValues = null;

            // First pass processing the libraries, collecting them by packageName,
            // and ignoring the ones that have the same package name as the application
            // (since that R class was already created).
            String appPackageName = aaptCommand.getPackageForR();
            if (appPackageName == null) {
                appPackageName = VariantConfiguration.getManifestPackage(aaptCommand.getManifestFile());
            }

            // list of all the symbol loaders per package names.
            Multimap<String, SymbolLoader> libMap = ArrayListMultimap.create();

            for (SymbolFileProvider lib : aaptCommand.getLibraries()) {
                if (lib.isOptional()) {
                    continue;
                }
                String packageName = VariantConfiguration.getManifestPackage(lib.getManifest());
                if (appPackageName == null) {
                    continue;
                }

                if (appPackageName.equals(packageName)) {
                    if (enforceUniquePackageName) {
                        String msg = String.format(
                                "Error: A library uses the same package as this project: %s",
                                packageName);
                        throw new RuntimeException(msg);
                    }

                    // ignore libraries that have the same package name as the app
                    continue;
                }

                File rFile = lib.getSymbolFile();
                // if the library has no resource, this file won't exist.
                if (rFile.isFile()) {

                    // load the full values if that's not already been done.
                    // Doing it lazily allow us to support the case where there's no
                    // resources anywhere.
                    if (fullSymbolValues == null) {
                        fullSymbolValues = new SymbolLoader(new File(aaptCommand.getSymbolOutputDir(), "R.txt"),
                                mLogger);
                        fullSymbolValues.load();
                    }

                    SymbolLoader libSymbols = new SymbolLoader(rFile, mLogger);
                    libSymbols.load();


                    // store these symbols by associating them with the package name.
                    libMap.put(packageName, libSymbols);
                }
            }

            // now loop on all the package name, merge all the symbols to write, and write them
            for (String packageName : libMap.keySet()) {
                Collection<SymbolLoader> symbols = libMap.get(packageName);

                if (enforceUniquePackageName && symbols.size() > 1) {
                    String msg = String.format(
                            "Error: more than one library with package name '%s'", packageName);
                    throw new RuntimeException(msg);
                }

                SymbolWriter writer = new SymbolWriter(aaptCommand.getSourceOutputDir(), packageName,
                        fullSymbolValues);
                for (SymbolLoader symbolLoader : symbols) {
                    writer.addSymbolsToWrite(symbolLoader);
                }
                writer.write();
            }
        }
    }

    public void generateApkData(
            @NonNull File apkFile,
            @NonNull File outResFolder,
            @NonNull String mainPkgName,
            @NonNull String resName) throws ProcessException, IOException {

        // need to run aapt to get apk information
        BuildToolInfo buildToolInfo = mTargetInfo.getBuildTools();

        String aapt = buildToolInfo.getPath(BuildToolInfo.PathId.AAPT);
        if (aapt == null) {
            throw new IllegalStateException(
                    "Unable to get aapt location from Build Tools " + buildToolInfo.getRevision());
        }

        ApkInfoParser parser = new ApkInfoParser(new File(aapt), mProcessExecutor);
        ApkInfoParser.ApkInfo apkInfo = parser.parseApk(apkFile);

        if (!apkInfo.getPackageName().equals(mainPkgName)) {
            throw new RuntimeException("The main and the micro apps do not have the same package name.");
        }

        String content = String.format(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                        "<wearableApp package=\"%1$s\">\n" +
                        "    <versionCode>%2$s</versionCode>\n" +
                        "    <versionName>%3$s</versionName>\n" +
                        "    <rawPathResId>%4$s</rawPathResId>\n" +
                        "</wearableApp>",
                apkInfo.getPackageName(),
                apkInfo.getVersionCode(),
                apkInfo.getVersionName(),
                resName);

        // xml folder
        File resXmlFile = new File(outResFolder, FD_RES_XML);
        FileUtils.mkdirs(resXmlFile);

        Files.write(content,
                new File(resXmlFile, ANDROID_WEAR_MICRO_APK + DOT_XML),
                Charsets.UTF_8);
    }

    public static void generateApkDataEntryInManifest(
            int minSdkVersion,
            int targetSdkVersion,
            @NonNull File manifestFile)
            throws InterruptedException, LoggedErrorException, IOException {

        StringBuilder content = new StringBuilder();
        content.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                .append("<manifest package=\"\" xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
                .append("            <uses-sdk android:minSdkVersion=\"")
                .append(minSdkVersion).append("\"");
        if (targetSdkVersion != -1) {
            content.append(" android:targetSdkVersion=\"").append(targetSdkVersion).append("\"");
        }
        content.append("/>\n");
        content.append("    <application>\n")
                .append("        <meta-data android:name=\"" + ANDROID_WEAR + "\"\n")
                .append("                   android:resource=\"@xml/" + ANDROID_WEAR_MICRO_APK)
                .append("\" />\n")
                .append("   </application>\n")
                .append("</manifest>\n");

        Files.write(content, manifestFile, Charsets.UTF_8);
    }

    /**
     * Compiles all the aidl files found in the given source folders.
     *
     * @param sourceFolders all the source folders to find files to compile
     * @param sourceOutputDir the output dir in which to generate the source code
     * @param packagedOutputDir the output dir for the AIDL files that will be packaged in an aar
     * @param packageWhiteList a list of AIDL FQCNs that are not parcelable that should also be
     *                         packaged in an aar
     * @param importFolders import folders
     * @param dependencyFileProcessor the dependencyFileProcessor to record the dependencies
     *                                of the compilation.
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void compileAllAidlFiles(@NonNull List<File> sourceFolders,
                                    @NonNull File sourceOutputDir,
                                    @Nullable File packagedOutputDir,
                                    @Nullable Collection<String> packageWhiteList,
                                    @NonNull List<File> importFolders,
                                    @Nullable DependencyFileProcessor dependencyFileProcessor,
                                    @NonNull ProcessOutputHandler processOutputHandler)
            throws IOException, InterruptedException, LoggedErrorException, ProcessException {
        checkNotNull(sourceFolders, "sourceFolders cannot be null.");
        checkNotNull(sourceOutputDir, "sourceOutputDir cannot be null.");
        checkNotNull(importFolders, "importFolders cannot be null.");
        checkState(mTargetInfo != null,
                "Cannot call compileAllAidlFiles() before setTargetInfo() is called.");

        IAndroidTarget target = mTargetInfo.getTarget();
        BuildToolInfo buildToolInfo = mTargetInfo.getBuildTools();

        String aidl = buildToolInfo.getPath(BuildToolInfo.PathId.AIDL);
        if (aidl == null || !new File(aidl).isFile()) {
            throw new IllegalStateException("aidl is missing");
        }

        List<File> fullImportList = Lists.newArrayListWithCapacity(
                sourceFolders.size() + importFolders.size());
        fullImportList.addAll(sourceFolders);
        fullImportList.addAll(importFolders);

        AidlProcessor processor = new AidlProcessor(
                aidl,
                target.getPath(IAndroidTarget.ANDROID_AIDL),
                fullImportList,
                sourceOutputDir,
                packagedOutputDir,
                packageWhiteList,
                dependencyFileProcessor != null ?
                        dependencyFileProcessor : sNoOpDependencyFileProcessor,
                mProcessExecutor,
                processOutputHandler);

        SourceSearcher searcher = new SourceSearcher(sourceFolders, "aidl");
        searcher.setUseExecutor(true);
        searcher.search(processor);
    }

    /**
     * Compiles the given aidl file.
     *
     * @param aidlFile the AIDL file to compile
     * @param sourceOutputDir the output dir in which to generate the source code
     * @param importFolders all the import folders, including the source folders.
     * @param dependencyFileProcessor the dependencyFileProcessor to record the dependencies
     *                                of the compilation.
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void compileAidlFile(@NonNull File sourceFolder,
                                @NonNull File aidlFile,
                                @NonNull File sourceOutputDir,
                                @Nullable File packagedOutputDir,
                                @Nullable Collection<String> packageWhitelist,
                                @NonNull List<File> importFolders,
                                @Nullable DependencyFileProcessor dependencyFileProcessor,
                                @NonNull ProcessOutputHandler processOutputHandler)
            throws IOException, InterruptedException, LoggedErrorException, ProcessException {
        checkNotNull(aidlFile, "aidlFile cannot be null.");
        checkNotNull(sourceOutputDir, "sourceOutputDir cannot be null.");
        checkNotNull(importFolders, "importFolders cannot be null.");
        checkState(mTargetInfo != null,
                "Cannot call compileAidlFile() before setTargetInfo() is called.");

        IAndroidTarget target = mTargetInfo.getTarget();
        BuildToolInfo buildToolInfo = mTargetInfo.getBuildTools();

        String aidl = buildToolInfo.getPath(BuildToolInfo.PathId.AIDL);
        if (aidl == null || !new File(aidl).isFile()) {
            throw new IllegalStateException("aidl is missing");
        }

        AidlProcessor processor = new AidlProcessor(
                aidl,
                target.getPath(IAndroidTarget.ANDROID_AIDL),
                importFolders,
                sourceOutputDir,
                packagedOutputDir,
                packageWhitelist,
                dependencyFileProcessor != null ?
                        dependencyFileProcessor : sNoOpDependencyFileProcessor,
                mProcessExecutor,
                processOutputHandler);

        processor.processFile(sourceFolder, aidlFile);
    }

    /**
     * Compiles all the renderscript files found in the given source folders.
     *
     * Right now this is the only way to compile them as the renderscript compiler requires all
     * renderscript files to be passed for all compilation.
     *
     * Therefore whenever a renderscript file or header changes, all must be recompiled.
     *
     * @param sourceFolders all the source folders to find files to compile
     * @param importFolders all the import folders.
     * @param sourceOutputDir the output dir in which to generate the source code
     * @param resOutputDir the output dir in which to generate the bitcode file
     * @param targetApi the target api
     * @param debugBuild whether the build is debug
     * @param optimLevel the optimization level
     * @param ndkMode whether the renderscript code should be compiled to generate C/C++ bindings
     * @param supportMode support mode flag to generate .so files.
     * @param abiFilters ABI filters in case of support mode
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void compileAllRenderscriptFiles(
            @NonNull List<File> sourceFolders,
            @NonNull List<File> importFolders,
            @NonNull File sourceOutputDir,
            @NonNull File resOutputDir,
            @NonNull File objOutputDir,
            @NonNull File libOutputDir,
            int targetApi,
            boolean debugBuild,
            int optimLevel,
            boolean ndkMode,
            boolean supportMode,
            @Nullable Set<String> abiFilters,
            @NonNull ProcessOutputHandler processOutputHandler)
            throws InterruptedException, ProcessException, LoggedErrorException, IOException {
        checkNotNull(sourceFolders, "sourceFolders cannot be null.");
        checkNotNull(importFolders, "importFolders cannot be null.");
        checkNotNull(sourceOutputDir, "sourceOutputDir cannot be null.");
        checkNotNull(resOutputDir, "resOutputDir cannot be null.");
        checkState(mTargetInfo != null,
                "Cannot call compileAllRenderscriptFiles() before setTargetInfo() is called.");

        BuildToolInfo buildToolInfo = mTargetInfo.getBuildTools();

        String renderscript = buildToolInfo.getPath(BuildToolInfo.PathId.LLVM_RS_CC);
        if (renderscript == null || !new File(renderscript).isFile()) {
            throw new IllegalStateException("llvm-rs-cc is missing");
        }

        RenderScriptProcessor processor = new RenderScriptProcessor(
                sourceFolders,
                importFolders,
                sourceOutputDir,
                resOutputDir,
                objOutputDir,
                libOutputDir,
                buildToolInfo,
                targetApi,
                debugBuild,
                optimLevel,
                ndkMode,
                supportMode,
                abiFilters);
        processor.build(mProcessExecutor, processOutputHandler);
    }

    /**
     * Computes and returns the leaf folders based on a given file extension.
     *
     * This looks through all the given root import folders, and recursively search for leaf
     * folders containing files matching the given extensions. All the leaf folders are gathered
     * and returned in the list.
     *
     * @param extension the extension to search for.
     * @param importFolders an array of list of root folders.
     * @return a list of leaf folder, never null.
     */
    @NonNull
    public static List<File> getLeafFolders(@NonNull String extension, List<File>... importFolders) {
        List<File> results = Lists.newArrayList();

        if (importFolders != null) {
            for (List<File> folders : importFolders) {
                SourceSearcher searcher = new SourceSearcher(folders, extension);
                searcher.setUseExecutor(false);
                LeafFolderGatherer processor = new LeafFolderGatherer();
                try {
                    searcher.search(processor);
                } catch (InterruptedException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                } catch (IOException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                } catch (LoggedErrorException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                } catch (ProcessException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                }

                results.addAll(processor.getFolders());
            }
        }

        return results;
    }

    /**
     * Converts the bytecode to Dalvik format
     * @param inputs the input files
     * @param outDexFolder the location of the output folder
     * @param dexOptions dex options
     * @param additionalParameters list of additional parameters to give to dx
     * @param incremental true if it should attempt incremental dex if applicable
     * @param instantRunMode true if we are invoking dex to convert classes while creating
     *                       instant-run related artifacts.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws ProcessException
     */
    public void convertByteCode(
            @NonNull Collection<File> inputs,
            @NonNull File outDexFolder,
                     boolean multidex,
            @Nullable File mainDexList,
            @NonNull DexOptions dexOptions,
            @Nullable List<String> additionalParameters,
            boolean incremental,
            boolean optimize,
            @NonNull ProcessOutputHandler processOutputHandler,
            final boolean instantRunMode)
            throws IOException, InterruptedException, ProcessException {
        checkNotNull(inputs, "inputs cannot be null.");
        checkNotNull(outDexFolder, "outDexFolder cannot be null.");
        checkNotNull(dexOptions, "dexOptions cannot be null.");
        checkArgument(outDexFolder.isDirectory(), "outDexFolder must be a folder");
        checkState(mTargetInfo != null,
                "Cannot call convertByteCode() before setTargetInfo() is called.");

        ImmutableList.Builder<File> verifiedInputs = ImmutableList.builder();
        for (File input : inputs) {
            if (checkLibraryClassesJar(input)) {
                verifiedInputs.add(input);
            }
        }

        DexProcessBuilder builder = new DexProcessBuilder(outDexFolder);

        builder.setVerbose(mVerboseExec)
                .setIncremental(incremental)
                .setNoOptimize(!optimize)
                .setMultiDex(multidex)
                .setMainDexList(mainDexList)
                .addInputs(verifiedInputs.build());

        if (additionalParameters != null) {
            builder.additionalParameters(additionalParameters);
        }

        runDexer(builder, dexOptions, processOutputHandler, instantRunMode);
    }

    private static final Object LOCK_FOR_DEX = new Object();
    private static final AtomicInteger DEX_PROCESS_COUNT = new AtomicInteger(2);
    private static ExecutorService sDexExecutorService = null;

    private void runDexer(
            @NonNull final DexProcessBuilder builder,
            @NonNull final DexOptions dexOptions,
            @NonNull final ProcessOutputHandler processOutputHandler,
            final boolean instantRunMode)
            throws ProcessException, IOException, InterruptedException {


        Revision buildToolsVersion = mTargetInfo.getBuildTools().getRevision();

        if (shouldDexInProcess(builder, dexOptions, buildToolsVersion, instantRunMode, getLogger())) {
            File dxJar = new File(
                    mTargetInfo.getBuildTools().getPath(BuildToolInfo.PathId.DX_JAR));
            DexWrapper dexWrapper = DexWrapper.obtain(dxJar);
            try {
                ProcessResult result =
                        dexWrapper.run(builder, dexOptions, processOutputHandler, mLogger);
                result.assertNormalExitValue();
            } finally {
                dexWrapper.release();
            }
        } else {

            // allocate the executorService if necessary
            synchronized (LOCK_FOR_DEX) {
                if (sDexExecutorService == null) {
                    if (dexOptions.getMaxProcessCount() != null) {
                        DEX_PROCESS_COUNT.set(dexOptions.getMaxProcessCount());
                    }
                    getLogger().info("Allocated dexExecutorService of size %d", DEX_PROCESS_COUNT
                            .get());
                    sDexExecutorService = Executors.newFixedThreadPool(DEX_PROCESS_COUNT.get());
                } else {
                    // check whether our executor service has the same number of max processes as
                    // this module requests, and print a warning if necessary.
                    if (dexOptions.getMaxProcessCount() != null
                            && dexOptions.getMaxProcessCount() != DEX_PROCESS_COUNT.get()) {
                        getLogger().warning("Module requested a maximum number of %d concurrent"
                                        + " dx processes but it was initialized earlier with %d,"
                                        + " setting is ignored",
                                dexOptions.getMaxProcessCount(),
                                DEX_PROCESS_COUNT.get());
                    }
                }
            }

            try {
                final String submission = Joiner.on(',').join(builder.getInputs());
                // this is a hack, we always spawn a new process for dependencies.jar so it does
                // get built in parallel with the slices, this is only valid for InstantRun mode.
                if (submission.contains("dependencies.jar")) {
                    Stopwatch stopwatch = Stopwatch.createStarted();
                    JavaProcessInfo javaProcessInfo = builder.build(mTargetInfo.getBuildTools(), dexOptions);
                    ProcessResult result = mJavaProcessExecutor.execute(javaProcessInfo,
                            processOutputHandler);
                    result.rethrowFailure().assertNormalExitValue();
                    getLogger().info("Dexing " + submission + " took " + stopwatch.toString());
                } else {
                    sDexExecutorService.submit(new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            Stopwatch stopwatch = Stopwatch.createStarted();
                            JavaProcessInfo javaProcessInfo = builder
                                    .build(mTargetInfo.getBuildTools(), dexOptions);
                            ProcessResult result = mJavaProcessExecutor.execute(javaProcessInfo,
                                    processOutputHandler);
                            result.rethrowFailure().assertNormalExitValue();
                            getLogger().info(
                                    "Dexing " + submission + " took " + stopwatch.toString());
                            return null;
                        }
                    }).get();
                }
            } catch (ExecutionException e) {
                throw new ProcessException(e);
            }
        }
    }

    /**
     * Determine whether to dex in process.
     *
     * If dexOptions.dexInProcess is true then throw RuntimeExceptions if conditions are not met.
     *
     * Otherwise if instantRunMode is enabled, quietly try to check if only a small number of
     * files will be dexed.
     *
     */
    @VisibleForTesting
    static boolean shouldDexInProcess(
            @NonNull DexProcessBuilder builder,
            @NonNull DexOptions dexOptions,
            @NonNull Revision buildToolsVersion,
            boolean instantRunMode,
            @NonNull ILogger logger) {

        return false;

        //
        //// Version that supports all flags that we know about, including numThreads.
        //Revision minimumBuildTools = DexProcessBuilder.FIXED_DX_MERGER;
        //
        //if (buildToolsVersion.compareTo(minimumBuildTools) < 0) {
        //    throw new RuntimeException("Running dex in-process requires build tools "
        //            + minimumBuildTools.toShortString());
        //}
        //
        //if (!DexWrapper.noMainDexOnClasspath()) {
        //    logger.warning("dx.jar is on Android Gradle plugin classpath, which will cause issues "
        //            + "with dexing in process, reverted to out of process.");
        //    return false;
        //}
        //
        //// Requested memory for dex
        //long requestedHeapSize = parseHeapSize(dexOptions.getJavaMaxHeapSize());
        //long maxMemory = Runtime.getRuntime().maxMemory();
        ////TODO: Is this the right heuristic?
        //
        //if (requestedHeapSize > maxMemory) {
        //    String dslRequest = dexOptions.getJavaMaxHeapSize();
        //    logger.warning(String.format(
        //            "To run dex in process, the Gradle daemon needs a larger heap. "
        //                    + "It currently has %1$d MB.\n"
        //                    + "For faster builds, increase the maximum heap size for the "
        //                    + "Gradle daemon to more than %2$s.\n"
        //                    + "To do this, set org.gradle.jvmargs=-Xmx%2$s in the "
        //                    + "project gradle.properties.\n"
        //                    + "For more information see "
        //                    + "https://docs.gradle.org/current/userguide/build_environment.html\n",
        //            maxMemory / (1024 * 1024),
        //            (dslRequest == null) ? "1G" :
        //                    dslRequest + " " + "as specified in dexOptions.javaMaxHeapSize"));
        //    return false;
        //}
        //return true;
    }

    private static final long DEFAULT_DEX_HEAP_SIZE = 1024 * 1024 * 1024; // 1 GiB

    @VisibleForTesting
    static long parseHeapSize(@Nullable String sizeParameter) {
        if (sizeParameter == null) {
            return DEFAULT_DEX_HEAP_SIZE;
        }
        long multiplier = 1;
        if (SdkUtils.endsWithIgnoreCase(sizeParameter ,"k")) {
            multiplier = 1024;
        } else if (SdkUtils.endsWithIgnoreCase(sizeParameter ,"m")) {
            multiplier = 1024 * 1024;
        } else if (SdkUtils.endsWithIgnoreCase(sizeParameter ,"g")) {
            multiplier = 1024 * 1024 * 1024;
        }

        if (multiplier != 1) {
            sizeParameter = sizeParameter.substring(0, sizeParameter.length() - 1);
        }

        try {
            return multiplier * Long.parseLong(sizeParameter);
        } catch (NumberFormatException e) {
            return DEFAULT_DEX_HEAP_SIZE;
        }
    }

    public Set<String> createMainDexList(
            @NonNull File allClassesJarFile,
            @NonNull File jarOfRoots) throws ProcessException {

        BuildToolInfo buildToolInfo = mTargetInfo.getBuildTools();
        ProcessInfoBuilder builder = new ProcessInfoBuilder();

        String dx = buildToolInfo.getPath(BuildToolInfo.PathId.DX_JAR);
        if (dx == null || !new File(dx).isFile()) {
            throw new IllegalStateException("dx.jar is missing");
        }

        builder.setClasspath(dx);
        builder.setMain("com.android.multidex.ClassReferenceListBuilder");

        builder.addArgs(jarOfRoots.getAbsolutePath());
        builder.addArgs(allClassesJarFile.getAbsolutePath());

        CachedProcessOutputHandler processOutputHandler = new CachedProcessOutputHandler();

        mJavaProcessExecutor.execute(builder.createJavaProcess(), processOutputHandler)
                .rethrowFailure()
                .assertNormalExitValue();

        String content = processOutputHandler.getProcessOutput().getStandardOutputAsString();

        return Sets.newHashSet(Splitter.on('\n').split(content));
    }

    /**
     * Converts the bytecode to Dalvik format, using the {@link PreDexCache} layer.
     *
     * @param inputFile the input file
     * @param outFile the output file or folder if multi-dex is enabled.
     * @param multiDex whether multidex is enabled.
     * @param dexOptions dex options
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws ProcessException
     */
    public void preDexLibrary(
            @NonNull File inputFile,
            @NonNull File outFile,
                     boolean multiDex,
            @NonNull DexOptions dexOptions,
            @NonNull ProcessOutputHandler processOutputHandler)
            throws IOException, InterruptedException, ProcessException {
        checkState(mTargetInfo != null,
                "Cannot call preDexLibrary() before setTargetInfo() is called.");

        PreDexCache.getCache().preDexLibrary(
                this,
                inputFile,
                outFile,
                multiDex,
                dexOptions,
                processOutputHandler);
    }

    /**
     * Converts the bytecode to Dalvik format, ignoring the {@link PreDexCache} layer.
     *
     * @param inputFile the input file
     * @param outFile the output file or folder if multi-dex is enabled.
     * @param multiDex whether multidex is enabled.
     * @param dexOptions the dex options
     * @return the list of generated files.
     *
     * @throws ProcessException
     */
    @NonNull
    public ImmutableList<File> preDexLibraryNoCache(
            @NonNull File inputFile,
            @NonNull File outFile,
            boolean multiDex,
            @NonNull DexOptions dexOptions,
            @NonNull ProcessOutputHandler processOutputHandler)
            throws ProcessException, IOException, InterruptedException {
        checkNotNull(inputFile, "inputFile cannot be null.");
        checkNotNull(outFile, "outFile cannot be null.");
        checkNotNull(dexOptions, "dexOptions cannot be null.");

        try {
            if (!checkLibraryClassesJar(inputFile)) {
                return ImmutableList.of();
            }
        } catch(IOException e) {
            throw new RuntimeException("Exception while checking library jar", e);
        }
        DexProcessBuilder builder = new DexProcessBuilder(outFile);

        builder.setVerbose(mVerboseExec)
                .setMultiDex(multiDex)
                .addInput(inputFile);

        runDexer(builder, dexOptions, processOutputHandler, false /* instantRunMode */);

        if (multiDex) {
            File[] files = outFile.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File file, String name) {
                    return name.endsWith(DOT_DEX);
                }
            });

            if (files == null || files.length == 0) {
                throw new RuntimeException("No dex files created at " + outFile.getAbsolutePath());
            }

            return ImmutableList.copyOf(files);
        } else {
            return ImmutableList.of(outFile);
        }
    }

    /**
     * Returns true if the library (jar or folder) contains class files, false otherwise.
     */
    private static boolean checkLibraryClassesJar(@NonNull File input) throws IOException {

        if (!input.exists()) {
            return false;
        }

        if (input.isDirectory()) {
            return checkFolder(input);
        }

        ZipFile zipFile = new ZipFile(input);
        try {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while(entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(DOT_CLASS) || name.endsWith(DOT_DEX)) {
                    return true;
                }
            }
            return false;
        } finally {
            zipFile.close();
        }
    }

    /**
     * Returns true if this folder or one of its subfolder contains a class file, false otherwise.
     */
    private static boolean checkFolder(@NonNull File folder) {
        File[] subFolders = folder.listFiles();
        if (subFolders != null) {
            for (File childFolder : subFolders) {
                if (childFolder.isFile()) {
                    String name = childFolder.getName();
                    if (name.endsWith(DOT_CLASS) || name.endsWith(DOT_DEX)) {
                        return true;
                    }
                }
                if (childFolder.isDirectory()) {
                    // if childFolder returns false, continue search otherwise return success.
                    if (checkFolder(childFolder)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Converts java source code into android byte codes using the jack integration APIs.
     * Jack will run in memory.
     */
    public boolean convertByteCodeUsingJackApis(
            @NonNull File dexOutputFolder,
            @NonNull File jackOutputFile,
            @NonNull Collection<File> classpath,
            @NonNull Collection<File> packagedLibraries,
            @NonNull Collection<File> sourceFiles,
            @Nullable Collection<File> proguardFiles,
            @Nullable File mappingFile,
            @NonNull Collection<File> jarJarRulesFiles,
            @Nullable File incrementalDir,
            @Nullable File javaResourcesFolder,
            boolean multiDex,
            int minSdkVersion) {

        BuildToolServiceLoader buildToolServiceLoader
                = BuildToolsServiceLoader.INSTANCE.forVersion(mTargetInfo.getBuildTools());

        Api01CompilationTask compilationTask = null;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            Optional<JackProvider> jackProvider = buildToolServiceLoader
                    .getSingleService(getLogger(), BuildToolsServiceLoader.JACK);
            if (jackProvider.isPresent()) {
                Api01Config config;

                // Get configuration object
                try {
                    config = jackProvider.get().createConfig(Api01Config.class);

                    config.setClasspath(new ArrayList<File>(classpath));
                    config.setOutputDexDir(dexOutputFolder);
                    config.setOutputJackFile(jackOutputFile);
                    config.setImportedJackLibraryFiles(new ArrayList<File>(packagedLibraries));

                    if (proguardFiles != null) {
                        config.setProguardConfigFiles(new ArrayList<File>(proguardFiles));
                    }

                    if (!jarJarRulesFiles.isEmpty()) {
                        config.setJarJarConfigFiles(ImmutableList.copyOf(jarJarRulesFiles));
                    }

                    if (multiDex) {
                        if (minSdkVersion < BuildToolInfo.SDK_LEVEL_FOR_MULTIDEX_NATIVE_SUPPORT) {
                            config.setMultiDexKind(MultiDexKind.LEGACY);
                        } else {
                            config.setMultiDexKind(MultiDexKind.NATIVE);
                        }
                    }

                    config.setSourceEntries(new ArrayList<File>(sourceFiles));
                    if (mappingFile != null) {
                        config.setProperty("jack.obfuscation.mapping.dump", "true");
                        config.setObfuscationMappingOutputFile(mappingFile);
                    }

                    config.setProperty("jack.import.resource.policy", "keep-first");

                    config.setReporter(ReporterKind.DEFAULT, outputStream);

                    // set the incremental dir if set and either already exists or can be created.
                    if (incrementalDir != null) {
                        if (!incrementalDir.exists() && !incrementalDir.mkdirs()) {
                            mLogger.warning("Cannot create %1$s directory, "
                                    + "jack incremental support disabled", incrementalDir);
                        }
                        if (incrementalDir.exists()) {
                            config.setIncrementalDir(incrementalDir);
                        }
                    }
                    if (javaResourcesFolder != null) {
                        ArrayList<File> folders = Lists.newArrayListWithExpectedSize(3);
                        folders.add(javaResourcesFolder);
                        config.setResourceDirs(folders);
                    }

                    compilationTask = config.getTask();
                } catch (ConfigNotSupportedException e1) {
                    mLogger.warning("Jack APIs v01 not supported");
                } catch (ConfigurationException e) {
                    mLogger.error(e,
                            "Jack APIs v01 configuration failed, reverting to native process");
                }
            }

            if (compilationTask == null) {
                return false;
            }

            // Run the compilation
            try {
                compilationTask.run();
                mLogger.info(outputStream.toString());
                return true;
            } catch (CompilationException e) {
                mLogger.error(e, outputStream.toString());
            } catch (UnrecoverableException e) {
                mLogger.error(e,
                        "Something out of Jack control has happened: " + e.getMessage());
            } catch (ConfigurationException e) {
                mLogger.error(e, outputStream.toString());
            }
        } catch (ClassNotFoundException e) {
            getLogger().warning("Cannot load Jack APIs v01 " + e.getMessage());
            getLogger().warning("Reverting to native process invocation");
        }
        return false;
    }

    public void convertByteCodeWithJack(
            @NonNull File dexOutputFolder,
            @NonNull File jackOutputFile,
            @NonNull String classpath,
            @NonNull Collection<File> packagedLibraries,
            @NonNull File ecjOptionFile,
            @Nullable Collection<File> proguardFiles,
            @Nullable File mappingFile,
            @NonNull Collection<File> jarJarRuleFiles,
            boolean multiDex,
            int minSdkVersion,
            boolean debugLog,
            String javaMaxHeapSize,
            @NonNull ProcessOutputHandler processOutputHandler) throws ProcessException {
        JackProcessBuilder builder = new JackProcessBuilder();

        builder.setDebugLog(debugLog)
                .setVerbose(mVerboseExec)
                .setJavaMaxHeapSize(javaMaxHeapSize)
                .setClasspath(classpath)
                .setDexOutputFolder(dexOutputFolder)
                .setJackOutputFile(jackOutputFile)
                .addImportFiles(packagedLibraries)
                .setEcjOptionFile(ecjOptionFile);

        if (proguardFiles != null) {
            builder.addProguardFiles(proguardFiles).setMappingFile(mappingFile);
        }

        if (multiDex) {
            builder.setMultiDex(true).setMinSdkVersion(minSdkVersion);
        }

        builder.setJarJarRuleFiles(jarJarRuleFiles);

        mJavaProcessExecutor.execute(
                builder.build(mTargetInfo.getBuildTools()), processOutputHandler)
                .rethrowFailure().assertNormalExitValue();
    }

    /**
     * Converts the bytecode of a library to the jack format
     * @param inputFile the input file
     * @param outFile the location of the output classes.dex file
     * @param dexOptions dex options
     *
     * @throws ProcessException
     * @throws IOException
     * @throws InterruptedException
     */
    public void convertLibraryToJack(
            @NonNull File inputFile,
            @NonNull File outFile,
            @NonNull DexOptions dexOptions,
            @NonNull ProcessOutputHandler processOutputHandler)
            throws ProcessException, IOException, InterruptedException {
        checkState(mTargetInfo != null,
                "Cannot call preJackLibrary() before setTargetInfo() is called.");

        BuildToolInfo buildToolInfo = mTargetInfo.getBuildTools();

        JackConversionCache.getCache().convertLibrary(
                inputFile,
                outFile,
                dexOptions,
                buildToolInfo,
                mVerboseExec,
                mJavaProcessExecutor,
                processOutputHandler,
                mLogger);
    }

    public static List<File> convertLibaryToJackUsingApis(
            @NonNull File inputFile,
            @NonNull File outFile,
            @NonNull DexOptions dexOptions,
            @NonNull BuildToolInfo buildToolInfo,
            boolean verbose,
            @NonNull JavaProcessExecutor processExecutor,
            @NonNull ProcessOutputHandler processOutputHandler,
            @NonNull ILogger logger) throws ProcessException {

        BuildToolServiceLoader buildToolServiceLoader = BuildToolsServiceLoader.INSTANCE
                .forVersion(buildToolInfo);
        if (System.getenv("USE_JACK_API") != null) {
            try {
                Optional<JillProvider> jillProviderOptional = buildToolServiceLoader
                        .getSingleService(logger, BuildToolsServiceLoader.JILL);

                if (jillProviderOptional.isPresent()) {
                    com.android.jill.api.v01.Api01Config config =
                            jillProviderOptional.get().createConfig(
                                    com.android.jill.api.v01.Api01Config.class);

                    config.setInputJavaBinaryFile(inputFile);
                    config.setOutputJackFile(outFile);
                    config.setVerbose(verbose);

                    Api01TranslationTask translationTask = config.getTask();
                    translationTask.run();

                    return ImmutableList.of(outFile);
                }

            } catch (ClassNotFoundException e) {
                logger.warning("Cannot find the jill tool in the classpath, reverting to native");
            } catch (com.android.jill.api.ConfigNotSupportedException e) {
                logger.warning(e.getMessage() + ", reverting to native");
            } catch (com.android.jill.api.v01.ConfigurationException e) {
                logger.warning(e.getMessage() + ", reverting to native");
            } catch (TranslationException e) {
                logger.error(e, "In process translation failed, reverting to native, file a bug");
            }
        }
        return convertLibraryToJack(inputFile, outFile, dexOptions, buildToolInfo, verbose,
                processExecutor, processOutputHandler, logger);
    }

    public static List<File> convertLibraryToJack(
            @NonNull File inputFile,
            @NonNull File outFile,
            @NonNull DexOptions dexOptions,
            @NonNull BuildToolInfo buildToolInfo,
            boolean verbose,
            @NonNull JavaProcessExecutor processExecutor,
            @NonNull ProcessOutputHandler processOutputHandler,
            @NonNull ILogger logger)
            throws ProcessException {
        checkNotNull(inputFile, "inputFile cannot be null.");
        checkNotNull(outFile, "outFile cannot be null.");
        checkNotNull(dexOptions, "dexOptions cannot be null.");

        // launch dx: create the command line
        ProcessInfoBuilder builder = new ProcessInfoBuilder();

        String jill = buildToolInfo.getPath(BuildToolInfo.PathId.JILL);
        if (jill == null || !new File(jill).isFile()) {
            throw new IllegalStateException("jill.jar is missing");
        }

        builder.setClasspath(jill);
        builder.setMain("com.android.jill.Main");

        if (dexOptions.getJavaMaxHeapSize() != null) {
            builder.addJvmArg("-Xmx" + dexOptions.getJavaMaxHeapSize());
        }
        builder.addArgs(inputFile.getAbsolutePath());
        builder.addArgs("--output");
        builder.addArgs(outFile.getAbsolutePath());

        if (verbose) {
            builder.addArgs("--verbose");
        }

        logger.verbose(builder.toString());
        JavaProcessInfo javaProcessInfo = builder.createJavaProcess();
        ProcessResult result = processExecutor.execute(javaProcessInfo, processOutputHandler);
        result.rethrowFailure().assertNormalExitValue();

        return Collections.singletonList(outFile);
    }

    /**
     * Packages the apk.
     *
     * @param androidResPkgLocation the location of the packaged resource file
     * @param dexFolders the folder(s) with the dex file(s).
     * @param javaResourcesLocations the processed Java resource folders and/or jars
     * @param jniLibsLocations the folders containing jni shared libraries
     * @param abiFilters optional ABI filter
     * @param jniDebugBuild whether the app should include jni debug data
     * @param signingConfig the signing configuration
     * @param outApkLocation location of the APK.
     * @throws DuplicateFileException
     * @throws FileNotFoundException if the store location was not found
     * @throws KeytoolException
     * @throws PackagerException
     * @throws SigningException when the key cannot be read from the keystore
     *
     */
    public void packageApk(
            @NonNull String androidResPkgLocation,
            @NonNull Set<File> dexFolders,
            @NonNull Collection<File> javaResourcesLocations,
            @NonNull Collection<File> jniLibsLocations,
            @NonNull Set<String> abiFilters,
            boolean jniDebugBuild,
            @Nullable SigningConfig signingConfig,
            @NonNull String outApkLocation,
            int minSdkVersion)
            throws DuplicateFileException, FileNotFoundException,
            KeytoolException, PackagerException, SigningException {
        checkNotNull(androidResPkgLocation, "androidResPkgLocation cannot be null.");
        checkNotNull(outApkLocation, "outApkLocation cannot be null.");

        CertificateInfo certificateInfo = null;
        if (signingConfig != null && signingConfig.isSigningReady()) {
            //noinspection ConstantConditions - isSigningReady() called above.
            certificateInfo = KeystoreHelper.getCertificateInfo(signingConfig.getStoreType(),
                    signingConfig.getStoreFile(), signingConfig.getStorePassword(),
                    signingConfig.getKeyPassword(), signingConfig.getKeyAlias());
        }

        try {
            Packager packager = new Packager(
                    outApkLocation, androidResPkgLocation,
                    certificateInfo, mCreatedBy, mLogger,
                    minSdkVersion);

            // add dex folder to the apk root.
            if (!dexFolders.isEmpty()) {
                packager.addDexFiles(dexFolders);
            }

            packager.setJniDebugMode(jniDebugBuild);

            // add the output of the java resource merger
            for (File javaResourcesLocation : javaResourcesLocations) {
                packager.addResources(javaResourcesLocation);
            }

            // and the output of the native lib merger.
            for (File jniLibsLocation : jniLibsLocations) {
                packager.addNativeLibraries(jniLibsLocation, abiFilters);
            }

            packager.sealApk();
        } catch (SealedPackageException e) {
            // shouldn't happen since we control the package from start to end.
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a new split APK containing only code, this will only be functional on
     * MarshMallow and above devices.
     */
    public void packageCodeSplitApk(
            @NonNull String androidResPkgLocation,
            @NonNull File dexFile,
            @Nullable SigningConfig signingConfig,
            @NonNull String outApkLocation) throws
                FileNotFoundException, KeytoolException, PackagerException, DuplicateFileException {

        CertificateInfo certificateInfo = null;
        if (signingConfig != null && signingConfig.isSigningReady()) {
            //noinspection ConstantConditions - isSigningReady() called above.
            certificateInfo = KeystoreHelper.getCertificateInfo(signingConfig.getStoreType(),
                    signingConfig.getStoreFile(), signingConfig.getStorePassword(),
                    signingConfig.getKeyPassword(), signingConfig.getKeyAlias());
        }

        try {
            Packager packager = new Packager(
                    outApkLocation, androidResPkgLocation,
                    certificateInfo, mCreatedBy, mLogger,
                    23 /* minSdkVersion, MarshMallow */);

            packager.addFile(dexFile, "classes.dex");
            packager.sealApk();
        } catch (SealedPackageException e) {
            // shouldn't happen since we control the package from start to end.
            throw new RuntimeException(e);
        }

    }

    /**
     * Signs a single jar file using the passed {@link SigningConfig}.
     * @param in the jar file to sign.
     * @param signingConfig the signing configuration
     * @param out the file path for the signed jar.
     * @throws IOException
     * @throws KeytoolException
     * @throws SigningException
     * @throws NoSuchAlgorithmException
     * @throws SignedJarBuilder.IZipEntryFilter.ZipAbortException
     * @throws com.android.builder.signing.SigningException
     */
    public void signApk(File in, SigningConfig signingConfig, File out)
            throws IOException, KeytoolException, SigningException, NoSuchAlgorithmException,
            SignedJarBuilder.IZipEntryFilter.ZipAbortException,
            com.android.builder.signing.SigningException {

        CertificateInfo certificateInfo = null;
        if (signingConfig != null && signingConfig.isSigningReady()) {
            //noinspection ConstantConditions - isSigningReady() called above.
            certificateInfo = KeystoreHelper.getCertificateInfo(signingConfig.getStoreType(),
                    signingConfig.getStoreFile(), signingConfig.getStorePassword(),
                    signingConfig.getKeyPassword(), signingConfig.getKeyAlias());
        }

        SignedJarBuilder signedJarBuilder = new SignedJarBuilder(
                new FileOutputStream(out),
                certificateInfo != null ? certificateInfo.getKey() : null,
                certificateInfo != null ? certificateInfo.getCertificate() : null,
                Packager.getLocalVersion(), mCreatedBy, 1 /* minSdkVersion */);


        signedJarBuilder.writeZip(new FileInputStream(in));
        signedJarBuilder.close();

    }
}
