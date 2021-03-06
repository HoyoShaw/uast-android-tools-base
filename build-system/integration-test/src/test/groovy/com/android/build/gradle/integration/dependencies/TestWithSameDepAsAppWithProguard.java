/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.integration.dependencies;

import static com.android.build.gradle.integration.common.utils.TestFileUtils.appendToFile;

import com.android.build.gradle.integration.common.fixture.GradleTestProject;
import com.android.build.gradle.integration.common.fixture.app.AndroidTestApp;
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests the handling of test dependency.
 */
public class TestWithSameDepAsAppWithProguard {

    private static AndroidTestApp testApp = HelloWorldApp.noBuildFile();

    @ClassRule
    public static GradleTestProject project = GradleTestProject.builder()
            .fromTestApp(testApp)
            .create();

    @BeforeClass
    public static void setUp() throws IOException {
        appendToFile(project.getBuildFile(),
                "\n" +
                "apply plugin: \"com.android.application\"\n" +
                "\n" +
                "android {\n" +
                "  compileSdkVersion " + String.valueOf(GradleTestProject.DEFAULT_COMPILE_SDK_VERSION) + "\n" +
                "  buildToolsVersion \"" + GradleTestProject.DEFAULT_BUILD_TOOL_VERSION + "\"\n" +
                "\n" +
                "  defaultConfig {\n" +
                "    minSdkVersion 21\n" +
                "  }\n" +
                "\n" +
                "  buildTypes {\n" +
                "    debug {\n" +
                "      minifyEnabled true\n" +
                "      proguardFiles getDefaultProguardFile(\"proguard-android.txt\")\n" +
                "      }\n" +
                "  }\n" +
                "}\n" +
                "\n" +
                "dependencies {\n" +
                "  compile \"com.android.tools:annotations:+\"\n" +
                "  androidTestCompile \"com.android.tools:annotations:+\"\n" +
                "}\n");
    }

    @AfterClass
    public static void cleanUp() {
        project = null;
    }

    @Test
    public void testProguardOnTestVariantSucceeds() {
        project.execute("clean", "assembleDebugAndroidTest");
    }
}
