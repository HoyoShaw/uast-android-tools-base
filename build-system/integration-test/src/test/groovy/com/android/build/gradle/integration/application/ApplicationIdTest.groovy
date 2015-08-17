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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.truth.TruthHelper
import groovy.transform.CompileStatic
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/**
 * Test setting applicationId and applicationIdSuffix.
 */
@CompileStatic
class ApplicationIdTest {
    @ClassRule
    public static GradleTestProject project = GradleTestProject.builder()
            .fromTestApp(new HelloWorldApp())
            .create();

    @BeforeClass
    public static void setUp() {
        project.buildFile << """
apply plugin: "com.android.application"

android {
    compileSdkVersion $GradleTestProject.DEFAULT_COMPILE_SDK_VERSION
    buildToolsVersion "$GradleTestProject.DEFAULT_BUILD_TOOL_VERSION"

    defaultConfig {
        applicationId "com.example.applicationidtest"
        applicationIdSuffix "default"
    }

    buildTypes {
        debug {
            applicationIdSuffix ".debug"
        }
    }
    productFlavors {
        f1 {
            applicationIdSuffix "f1"
        }
    }
}
"""
    }

    @AfterClass
    static void cleanUp() {
        project = null
    }

    @Test
    public void "check application id"() {
        project.execute("assembleF1Debug");
        TruthHelper.assertThatApk(project.getApk("f1", "debug"))
                .hasPackageName("com.example.applicationidtest.default.f1.debug")
    }

}
