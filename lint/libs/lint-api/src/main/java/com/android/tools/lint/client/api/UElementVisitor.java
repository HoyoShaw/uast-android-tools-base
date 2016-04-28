/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.tools.lint.client.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Detector.JavaPsiScanner;
import com.android.tools.lint.detector.api.Detector.UastScanner;
import com.android.tools.lint.detector.api.Detector.XmlScanner;
import com.android.tools.lint.detector.api.JavaContext;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTypeParameter;

import org.jetbrains.uast.*;
import org.jetbrains.uast.visitor.AbstractUastVisitor;
import org.jetbrains.uast.visitor.UastVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Specialized visitor for running detectors on a Java AST.
 * It operates in three phases:
 * <ol>
 *   <li> First, it computes a set of maps where it generates a map from each
 *        significant AST attribute (such as method call names) to a list
 *        of detectors to consult whenever that attribute is encountered.
 *        Examples of "attributes" are method names, Android resource identifiers,
 *        and general AST node types such as "cast" nodes etc. These are
 *        defined on the {@link JavaPsiScanner} interface.
 *   <li> Second, it iterates over the document a single time, delegating to
 *        the detectors found at each relevant AST attribute.
 *   <li> Finally, it calls the remaining visitors (those that need to process a
 *        whole document on their own).
 * </ol>
 * It also notifies all the detectors before and after the document is processed
 * such that they can do pre- and post-processing.
 */
public class UElementVisitor {
    /** Default size of lists holding detectors of the same type for a given node type */
    private static final int SAME_TYPE_COUNT = 8;

    private final Map<String, List<VisitingDetector>> mMethodDetectors =
            Maps.newHashMapWithExpectedSize(80);
    private final Map<String, List<VisitingDetector>> mConstructorDetectors =
            Maps.newHashMapWithExpectedSize(12);
    private final Map<String, List<VisitingDetector>> mReferenceDetectors =
            Maps.newHashMapWithExpectedSize(10);
    private Set<String> mConstructorSimpleNames;
    private final List<VisitingDetector> mResourceFieldDetectors =
            new ArrayList<VisitingDetector>();
    private final List<VisitingDetector> mAllDetectors;
    private final List<VisitingDetector> mFullTreeDetectors;
    private final Map<Class<? extends UElement>, List<VisitingDetector>> mNodePsiTypeDetectors =
            new HashMap<Class<? extends UElement>, List<VisitingDetector>>(16);
    private final JavaParser mParser;
    private final Map<String, List<VisitingDetector>> mSuperClassDetectors =
            new HashMap<String, List<VisitingDetector>>();

    /**
     * Number of fatal exceptions (internal errors, usually from ECJ) we've
     * encountered; we don't log each and every one to avoid massive log spam
     * in code which triggers this condition
     */
    private static int sExceptionCount;
    /** Max number of logs to include */
    private static final int MAX_REPORTED_CRASHES = 20;

    UElementVisitor(@NonNull JavaParser parser, @NonNull List<Detector> detectors) {
        mParser = parser;
        mAllDetectors = new ArrayList<VisitingDetector>(detectors.size());
        mFullTreeDetectors = new ArrayList<VisitingDetector>(detectors.size());

        for (Detector detector : detectors) {
            UastScanner javaPsiScanner = (UastScanner) detector;
            VisitingDetector v = new VisitingDetector(detector, javaPsiScanner);
            mAllDetectors.add(v);

            List<String> applicableSuperClasses = detector.applicableSuperClasses();
            if (applicableSuperClasses != null) {
                for (String fqn : applicableSuperClasses) {
                    List<VisitingDetector> list = mSuperClassDetectors.get(fqn);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mSuperClassDetectors.put(fqn, list);
                    }
                    list.add(v);
                }
                continue;
            }

            List<Class<? extends UElement>> nodePsiTypes = detector.getApplicableUastTypes();
            if (nodePsiTypes != null) {
                for (Class<? extends UElement> type : nodePsiTypes) {
                    List<VisitingDetector> list = mNodePsiTypeDetectors.get(type);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mNodePsiTypeDetectors.put(type, list);
                    }
                    list.add(v);
                }
            }

            List<String> names = detector.getApplicableFunctionNames();
            if (names != null) {
                // not supported in Java visitors; adding a method invocation node is trivial
                // for that case.
                assert names != XmlScanner.ALL;

                for (String name : names) {
                    List<VisitingDetector> list = mMethodDetectors.get(name);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mMethodDetectors.put(name, list);
                    }
                    list.add(v);
                }
            }

            List<String> types = detector.getApplicableConstructorTypes();
            if (types != null) {
                // not supported in Java visitors; adding a method invocation node is trivial
                // for that case.
                assert types != XmlScanner.ALL;
                if (mConstructorSimpleNames == null) {
                    mConstructorSimpleNames = Sets.newHashSet();
                }
                for (String type : types) {
                    List<VisitingDetector> list = mConstructorDetectors.get(type);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mConstructorDetectors.put(type, list);
                        mConstructorSimpleNames.add(type.substring(type.lastIndexOf('.')+1));
                    }
                    list.add(v);
                }
            }

            List<String> referenceNames = detector.getApplicableReferenceNames();
            if (referenceNames != null) {
                // not supported in Java visitors; adding a method invocation node is trivial
                // for that case.
                assert referenceNames != XmlScanner.ALL;

                for (String name : referenceNames) {
                    List<VisitingDetector> list = mReferenceDetectors.get(name);
                    if (list == null) {
                        list = new ArrayList<VisitingDetector>(SAME_TYPE_COUNT);
                        mReferenceDetectors.put(name, list);
                    }
                    list.add(v);
                }
            }

            if (detector.appliesToResourceRefs()) {
                mResourceFieldDetectors.add(v);
            } else if ((referenceNames == null || referenceNames.isEmpty())
                    && (nodePsiTypes == null || nodePsiTypes.isEmpty())
                    && (types == null || types.isEmpty())) {
                mFullTreeDetectors.add(v);
            }
        }
    }

    void visitFile(@NonNull final JavaContext context) {
        try {
            Project ideaProject = context.getParser().getIdeaProject();
            if (ideaProject == null) {
                return;
            }

            VirtualFile virtualFile = StandardFileSystems.local()
                    .findFileByPath(context.file.getAbsolutePath());
            if (virtualFile == null) {
                return;
            }

            PsiFile psiFile = PsiManager.getInstance(ideaProject).findFile(virtualFile);
            if (psiFile == null) {
                return;
            }

            UElement uElement = context.convert(psiFile);
            if (!(uElement instanceof UFile)) {
                // No need to log this; the parser should be reporting
                // a full warning (such as IssueRegistry#PARSER_ERROR)
                // with details, location, etc.
                return;
            }

            final UFile uFile = (UFile) uElement;

            try {
                context.setUFile(uFile);

                mParser.runReadAction(new Runnable() {
                    @Override
                    public void run() {
                        for (VisitingDetector v : mAllDetectors) {
                            v.setContext(context);
                            v.getDetector().beforeCheckFile(context);
                        }
                    }
                });

                if (!mSuperClassDetectors.isEmpty()) {
                    mParser.runReadAction(new Runnable() {
                        @Override
                        public void run() {
                            SuperclassPsiVisitor visitor = new SuperclassPsiVisitor(context);
                            uFile.accept(visitor);
                        }
                    });
                }

                for (final VisitingDetector v : mFullTreeDetectors) {
                    mParser.runReadAction(new Runnable() {
                        @Override
                        public void run() {
                            UastVisitor visitor = v.getVisitor();
                            uFile.accept(visitor);
                        }
                    });
                }

                if (!mMethodDetectors.isEmpty()
                        || !mResourceFieldDetectors.isEmpty()
                        || !mConstructorDetectors.isEmpty()
                        || !mReferenceDetectors.isEmpty()) {
                    mParser.runReadAction(new Runnable() {
                        @Override
                        public void run() {
                            // TODO: Do we need to break this one up into finer grain
                            // locking units
                            UastVisitor visitor = new DelegatingPsiVisitor(context);
                            uFile.accept(visitor);
                        }
                    });
                } else {
                    if (!mNodePsiTypeDetectors.isEmpty()) {
                        mParser.runReadAction(new Runnable() {
                            @Override
                            public void run() {
                                // TODO: Do we need to break this one up into finer grain
                                // locking units
                                UastVisitor visitor = new DispatchPsiVisitor();
                                uFile.accept(visitor);
                            }
                        });
                    }
                }

                mParser.runReadAction(new Runnable() {
                    @Override
                    public void run() {
                        for (VisitingDetector v : mAllDetectors) {
                            v.getDetector().afterCheckFile(context);
                        }
                    }
                });
            } catch (Throwable e) {
                int a = 5;
            } finally {
                mParser.dispose(context, uFile);
                context.setUFile(null);
            }
        } catch (ProcessCanceledException ignore) {
            // Cancelling inspections in the IDE
        } catch (RuntimeException e) {
            if (sExceptionCount++ > MAX_REPORTED_CRASHES) {
                // No need to keep spamming the user that a lot of the files
                // are tripping up ECJ, they get the picture.
                return;
            }

            if (e.getClass().getSimpleName().equals("IndexNotReadyException")) {
                // Attempting to access PSI during startup before indices are ready; ignore these.
                // See http://b.android.com/176644 for an example.
                return;
            }

            // Work around ECJ bugs; see https://code.google.com/p/android/issues/detail?id=172268
            // Don't allow lint bugs to take down the whole build. TRY to log this as a
            // lint error instead!
            StringBuilder sb = new StringBuilder(100);
            sb.append("Unexpected failure during lint analysis of ");
            sb.append(context.file.getName());
            sb.append(" (this is a bug in lint or one of the libraries it depends on)\n");

            sb.append(e.getClass().getSimpleName());
            sb.append(':');
            StackTraceElement[] stackTrace = e.getStackTrace();
            int count = 0;
            for (StackTraceElement frame : stackTrace) {
                if (count > 0) {
                    sb.append("<-");
                }

                String className = frame.getClassName();
                sb.append(className.substring(className.lastIndexOf('.') + 1));
                sb.append('.').append(frame.getMethodName());
                sb.append('(');
                sb.append(frame.getFileName()).append(':').append(frame.getLineNumber());
                sb.append(')');
                count++;
                // Only print the top 3-4 frames such that we can identify the bug
                if (count == 4) {
                    break;
                }
            }
            Throwable throwable = null; // NOT e: this makes for very noisy logs
            //noinspection ConstantConditions
            context.log(throwable, sb.toString());
        }
    }

    /**
     * For testing only: returns the number of exceptions thrown during Java AST analysis
     *
     * @return the number of internal errors found
     */
    @VisibleForTesting
    public static int getCrashCount() {
        return sExceptionCount;
    }

    /**
     * For testing only: clears the crash counter
     */
    @VisibleForTesting
    public static void clearCrashCount() {
        sExceptionCount = 0;
    }

    public void prepare(@NonNull List<JavaContext> contexts) {
        mParser.prepareJavaParse(contexts);
    }

    public void dispose() {
        mParser.dispose();
    }

    @Nullable
    private static Set<String> getInterfaceNames(
            @Nullable Set<String> addTo,
            @NonNull UClass cls,
            @NonNull JavaContext context) {
        for (UClass clazz : cls.getSuperClasses(context)) {
            if (clazz != null && clazz.getKind() == UastClassKind.INTERFACE) {
                String name = clazz.getFqName();
                if (addTo == null) {
                    addTo = Sets.newHashSet();
                } else if (addTo.contains(name)) {
                    // Superclasses can explicitly implement the same interface,
                    // so keep track of visited interfaces as we traverse up the
                    // super class chain to avoid checking the same interface
                    // more than once.
                    continue;
                }
                addTo.add(name);
                getInterfaceNames(addTo, clazz, context);
            }
        }

        return addTo;
    }

    private static class VisitingDetector {
        private UastVisitor mVisitor;
        private JavaContext mContext;
        public final Detector mDetector;
        public final UastScanner mJavaScanner;

        public VisitingDetector(@NonNull Detector detector, @NonNull UastScanner javaScanner) {
            mDetector = detector;
            mJavaScanner = javaScanner;
        }

        @NonNull
        public Detector getDetector() {
            return mDetector;
        }

        @Nullable
        public UastScanner getUastScanner() {
            return mJavaScanner;
        }

        public void setContext(@NonNull JavaContext context) {
            mContext = context;

            // The visitors are one-per-context, so clear them out here and construct
            // lazily only if needed
            mVisitor = null;
        }

        @NonNull
        UastVisitor getVisitor() {
            if (mVisitor == null) {
                mVisitor = mDetector.createUastVisitor(mContext);
                if (mVisitor == null) {
                    mVisitor = new AbstractUastVisitor() {};
                }
            }
            return mVisitor;
        }
    }

    private class SuperclassPsiVisitor extends AbstractUastVisitor {
        private JavaContext mContext;

        public SuperclassPsiVisitor(@NonNull JavaContext context) {
            mContext = context;
        }

        @Override
        public boolean visitClass(UClass node) {
            boolean result = super.visitClass(node);
            checkClass(node);
            return result;
        }

        private void checkClass(@NonNull UClass node) {
            if (node instanceof PsiTypeParameter) {
                // Not included: explained in javadoc for JavaPsiScanner#checkClass
                return;
            }

            UClass cls = node;
            int depth = 0;
            while (cls != null) {
                List<VisitingDetector> list = mSuperClassDetectors.get(cls.getFqName());
                if (list != null) {
                    for (VisitingDetector v : list) {
                        UastScanner javaPsiScanner = v.getUastScanner();
                        if (javaPsiScanner != null) {
                            javaPsiScanner.checkClass(mContext, node);
                        }
                    }
                }

                // Check interfaces too
                Set<String> interfaceNames = getInterfaceNames(null, cls, mContext);
                if (interfaceNames != null) {
                    for (String name : interfaceNames) {
                        list = mSuperClassDetectors.get(name);
                        if (list != null) {
                            for (VisitingDetector v : list) {
                                UastScanner javaPsiScanner = v.getUastScanner();
                                if (javaPsiScanner != null) {
                                    javaPsiScanner.checkClass(mContext, node);
                                }
                            }
                        }
                    }
                }

                cls = cls.getSuperClass(mContext);
                depth++;
                if (depth == 500) {
                    // Shouldn't happen in practice; this prevents the IDE from
                    // hanging if the user has accidentally typed in an incorrect
                    // super class which creates a cycle.
                    break;
                }
            }
        }
    }

    private class DispatchPsiVisitor extends AbstractUastVisitor {
        @Override
        public boolean visitType(UType node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UType.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitType(node);
                }
            }
            return super.visitType(node);
        }

        @Override
        public boolean visitFile(UFile node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UFile.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitFile(node);
                }
            }
            return super.visitFile(node);
        }

        @Override
        public boolean visitImportStatement(UImportStatement node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UImportStatement.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitImportStatement(node);
                }
            }
            return super.visitImportStatement(node);
        }

        @Override
        public boolean visitAnnotation(UAnnotation node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UAnnotation.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitAnnotation(node);
                }
            }
            return super.visitAnnotation(node);
        }

        @Override
        public boolean visitCatchClause(UCatchClause node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UCatchClause.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCatchClause(node);
                }
            }
            return super.visitCatchClause(node);
        }

        @Override
        public boolean visitClass(UClass node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UClass.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitClass(node);
                }
            }
            return super.visitClass(node);
        }

        @Override
        public boolean visitFunction(UFunction node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UFunction.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitFunction(node);
                }
            }
            return super.visitFunction(node);
        }

        @Override
        public boolean visitVariable(UVariable node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UVariable.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitVariable(node);
                }
            }
            return super.visitVariable(node);
        }

        @Override
        public boolean visitLabeledExpression(ULabeledExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(ULabeledExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitLabeledExpression(node);
                }
            }
            return super.visitLabeledExpression(node);
        }

        @Override
        public boolean visitDeclarationsExpression(UDeclarationsExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UDeclarationsExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitDeclarationsExpression(node);
                }
            }
            return super.visitDeclarationsExpression(node);
        }

        @Override
        public boolean visitBlockExpression(UBlockExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UBlockExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBlockExpression(node);
                }
            }
            return super.visitBlockExpression(node);
        }

        @Override
        public boolean visitQualifiedExpression(UQualifiedExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UQualifiedExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitQualifiedExpression(node);
                }
            }
            return super.visitQualifiedExpression(node);
        }

        @Override
        public boolean visitSimpleReferenceExpression(USimpleReferenceExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(USimpleReferenceExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSimpleReferenceExpression(node);
                }
            }
            return super.visitSimpleReferenceExpression(node);
        }

        @Override
        public boolean visitCallExpression(UCallExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UCallExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCallExpression(node);
                }
            }
            return super.visitCallExpression(node);
        }

        @Override
        public boolean visitBinaryExpression(UBinaryExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UBinaryExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBinaryExpression(node);
                }
            }
            return super.visitBinaryExpression(node);
        }

        @Override
        public boolean visitBinaryExpressionWithType(UBinaryExpressionWithType node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UBinaryExpressionWithType.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBinaryExpressionWithType(node);
                }
            }
            return super.visitBinaryExpressionWithType(node);
        }

        @Override
        public boolean visitParenthesizedExpression(UParenthesizedExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UParenthesizedExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitParenthesizedExpression(node);
                }
            }
            return super.visitParenthesizedExpression(node);
        }

        @Override
        public boolean visitUnaryExpression(UUnaryExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UUnaryExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitUnaryExpression(node);
                }
            }
            return super.visitUnaryExpression(node);
        }

        @Override
        public boolean visitPrefixExpression(UPrefixExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UPrefixExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitPrefixExpression(node);
                }
            }
            return super.visitPrefixExpression(node);
        }

        @Override
        public boolean visitPostfixExpression(UPostfixExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UPostfixExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitPostfixExpression(node);
                }
            }
            return super.visitPostfixExpression(node);
        }

        @Override
        public boolean visitSpecialExpressionList(USpecialExpressionList node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(USpecialExpressionList.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSpecialExpressionList(node);
                }
            }
            return super.visitSpecialExpressionList(node);
        }

        @Override
        public boolean visitIfExpression(UIfExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UIfExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitIfExpression(node);
                }
            }
            return super.visitIfExpression(node);
        }

        @Override
        public boolean visitSwitchExpression(USwitchExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(USwitchExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSwitchExpression(node);
                }
            }
            return super.visitSwitchExpression(node);
        }

        @Override
        public boolean visitSwitchClauseExpression(USwitchClauseExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(USwitchClauseExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSwitchClauseExpression(node);
                }
            }
            return super.visitSwitchClauseExpression(node);
        }

        @Override
        public boolean visitWhileExpression(UWhileExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UWhileExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitWhileExpression(node);
                }
            }
            return super.visitWhileExpression(node);
        }

        @Override
        public boolean visitDoWhileExpression(UDoWhileExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UDoWhileExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitDoWhileExpression(node);
                }
            }
            return super.visitDoWhileExpression(node);
        }

        @Override
        public boolean visitForExpression(UForExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UForExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitForExpression(node);
                }
            }
            return super.visitForExpression(node);
        }

        @Override
        public boolean visitForEachExpression(UForEachExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UForEachExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitForEachExpression(node);
                }
            }
            return super.visitForEachExpression(node);
        }

        @Override
        public boolean visitTryExpression(UTryExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UTryExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitTryExpression(node);
                }
            }
            return super.visitTryExpression(node);
        }

        @Override
        public boolean visitLiteralExpression(ULiteralExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(ULiteralExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitLiteralExpression(node);
                }
            }
            return super.visitLiteralExpression(node);
        }

        @Override
        public boolean visitThisExpression(UThisExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UThisExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitThisExpression(node);
                }
            }
            return super.visitThisExpression(node);
        }

        @Override
        public boolean visitSuperExpression(USuperExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(USuperExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitSuperExpression(node);
                }
            }
            return super.visitSuperExpression(node);
        }

        @Override
        public boolean visitReturnExpression(UReturnExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UReturnExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitReturnExpression(node);
                }
            }
            return super.visitReturnExpression(node);
        }

        @Override
        public boolean visitBreakExpression(UBreakExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UBreakExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitBreakExpression(node);
                }
            }
            return super.visitBreakExpression(node);
        }

        @Override
        public boolean visitContinueExpression(UContinueExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UContinueExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitContinueExpression(node);
                }
            }
            return super.visitContinueExpression(node);
        }

        @Override
        public boolean visitThrowExpression(UThrowExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UThrowExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitThrowExpression(node);
                }
            }
            return super.visitThrowExpression(node);
        }

        @Override
        public boolean visitArrayAccessExpression(UArrayAccessExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UArrayAccessExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitArrayAccessExpression(node);
                }
            }
            return super.visitArrayAccessExpression(node);
        }

        @Override
        public boolean visitCallableReferenceExpression(UCallableReferenceExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UCallableReferenceExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitCallableReferenceExpression(node);
                }
            }
            return super.visitCallableReferenceExpression(node);
        }

        @Override
        public boolean visitClassLiteralExpression(UClassLiteralExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UClassLiteralExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitClassLiteralExpression(node);
                }
            }
            return super.visitClassLiteralExpression(node);
        }

        @Override
        public boolean visitLambdaExpression(ULambdaExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(ULambdaExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitLambdaExpression(node);
                }
            }
            return super.visitLambdaExpression(node);
        }

        @Override
        public boolean visitObjectLiteralExpression(UObjectLiteralExpression node) {
            List<VisitingDetector> list = mNodePsiTypeDetectors.get(UObjectLiteralExpression.class);
            if (list != null) {
                for (VisitingDetector v : list) {
                    v.getVisitor().visitObjectLiteralExpression(node);
                }
            }
            return super.visitObjectLiteralExpression(node);
        }
    }

    /** Performs common AST searches for method calls and R-type-field references.
     * Note that this is a specialized form of the {@link DispatchPsiVisitor}. */
    private class DelegatingPsiVisitor extends DispatchPsiVisitor {
        private final JavaContext mContext;
        private final boolean mVisitResources;
        private final boolean mVisitMethods;
        private final boolean mVisitConstructors;
        private final boolean mVisitReferences;

        public DelegatingPsiVisitor(JavaContext context) {
            mContext = context;

            mVisitMethods = !mMethodDetectors.isEmpty();
            mVisitConstructors = !mConstructorDetectors.isEmpty();
            mVisitResources = !mResourceFieldDetectors.isEmpty();
            mVisitReferences = !mReferenceDetectors.isEmpty();
        }

        @Override
        public boolean visitSimpleReferenceExpression(USimpleReferenceExpression node) {
            if (mVisitReferences) {
                List<VisitingDetector> list = mReferenceDetectors.get(node.getIdentifier());
                if (list != null) {
                    UDeclaration referenced = node.resolve(mContext);
                    if (referenced != null) {
                        for (VisitingDetector v : list) {
                            UastScanner uastScanner = v.getUastScanner();
                            if (uastScanner != null) {
                                uastScanner.visitReference(mContext, v.getVisitor(),
                                        node, referenced);
                            }
                        }
                    }
                }
            }

            if (mVisitResources) {
                //TODO
                //// R.type.name
                //UQualifiedExpression qualified = getQualifiedResourceExpression(node);
                //if (node.getQualifier() instanceof PsiReferenceExpression) {
                //    PsiReferenceExpression select = (PsiReferenceExpression) node.getQualifier();
                //    if (select.getQualifier() instanceof PsiReferenceExpression) {
                //        PsiReferenceExpression reference = (PsiReferenceExpression) select.getQualifier();
                //        if (R_CLASS.equals(reference.getReferenceName())) {
                //            String typeName = select.getReferenceName();
                //            String name = node.getReferenceName();
                //
                //            ResourceType type = ResourceType.getEnum(typeName);
                //            if (type != null) {
                //                boolean isFramework =
                //                        reference.getQualifier() instanceof PsiReferenceExpression
                //                                && ANDROID_PKG.equals(((PsiReferenceExpression)reference.
                //                                getQualifier()).getReferenceName());
                //
                //                for (VisitingDetector v : mResourceFieldDetectors) {
                //                    JavaPsiScanner detector = v.getJavaScanner();
                //                    if (detector != null) {
                //                        //noinspection ConstantConditions
                //                        detector.visitResourceReference(mContext, v.getVisitor(),
                //                                node, type, name, isFramework);
                //                    }
                //                }
                //            }
                //
                //            return;
                //        }
                //    }
                //}
                //
                //// Arbitrary packages -- android.R.type.name, foo.bar.R.type.name
                //if (R_CLASS.equals(node.getReferenceName())) {
                //    PsiElement parent = node.getParent();
                //    if (parent instanceof PsiReferenceExpression) {
                //        PsiElement grandParent = parent.getParent();
                //        if (grandParent instanceof PsiReferenceExpression) {
                //            PsiReferenceExpression select = (PsiReferenceExpression) grandParent;
                //            String name = select.getReferenceName();
                //            PsiElement typeOperand = select.getQualifier();
                //            if (name != null && typeOperand instanceof PsiReferenceExpression) {
                //                PsiReferenceExpression typeSelect =
                //                        (PsiReferenceExpression) typeOperand;
                //                String typeName = typeSelect.getReferenceName();
                //                ResourceType type = typeName != null
                //                        ? ResourceType.getEnum(typeName)
                //                        : null;
                //                if (type != null) {
                //                    boolean isFramework = node.getQualifier().getText().equals(
                //                            ANDROID_PKG);
                //                    for (VisitingDetector v : mResourceFieldDetectors) {
                //                        JavaPsiScanner detector = v.getJavaScanner();
                //                        if (detector != null) {
                //                            detector.visitResourceReference(mContext,
                //                                    v.getVisitor(),
                //                                    node, type, name, isFramework);
                //                        }
                //                    }
                //                }
                //
                //                return;
                //            }
                //        }
                //    }
                //}
            }

            return super.visitSimpleReferenceExpression(node);
        }

        @Nullable
        private UQualifiedExpression getQualifiedResourceExpression(
                USimpleReferenceExpression node) {
            UElement parent = node.getParent();
            if (!(parent instanceof UQualifiedExpression)) {
                return null;
            }

            UElement parentParent = parent.getParent();
            if (!(parentParent instanceof UQualifiedExpression)) {
                return null;
            }

            return (UQualifiedExpression) parentParent;
        }

        @Override
        public boolean visitCallExpression(UCallExpression node) {
            boolean result = super.visitCallExpression(node);

            UastCallKind kind = node.getKind();
            if (kind == UastCallKind.FUNCTION_CALL) {
                visitMethodCallExpression(node);
            } else if (kind == UastCallKind.CONSTRUCTOR_CALL) {
                visitNewExpression(node);
            }

            return result;
        }

        private void visitMethodCallExpression(UCallExpression node) {
            if (mVisitMethods) {
                String methodName = node.getFunctionName();
                if (methodName != null) {
                    List<VisitingDetector> list = mMethodDetectors.get(methodName);
                    if (list != null) {
                        UFunction function = node.resolve(mContext);
                        if (function != null) {
                            for (VisitingDetector v : list) {
                                UastScanner scanner = v.getUastScanner();
                                if (scanner != null) {
                                    scanner.visitFunctionCallExpression(mContext,
                                            v.getVisitor(), node, function);
                                }
                            }
                        }
                    }
                }
            }
        }

        private void visitNewExpression(UCallExpression node) {
            if (mVisitConstructors) {
                UFunction resolvedConstructor = node.resolve(mContext);
                UClass resolvedClass = UastUtils.getContainingClass(resolvedConstructor);
                if (resolvedClass != null && resolvedConstructor != null) {
                    List<VisitingDetector> list = mConstructorDetectors.get(
                            resolvedClass.getFqName());
                    if (list != null) {
                        for (VisitingDetector v : list) {
                            UastScanner javaPsiScanner = v.getUastScanner();
                            if (javaPsiScanner != null) {
                                javaPsiScanner.visitConstructor(mContext,
                                        v.getVisitor(), node, resolvedConstructor);
                            }
                        }
                    }
                }
            }
        }
    }
}
