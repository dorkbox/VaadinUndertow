/*
 * Copyright 2021 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.vaadin.util

import com.vaadin.flow.server.Constants
import com.vaadin.flow.server.InitParameters
import com.vaadin.flow.server.frontend.FrontendUtils
import elemental.json.JsonObject
import elemental.json.impl.JsonUtil
import io.undertow.servlet.api.ServletInfo
import java.io.File

/**
 *
 */
class VaadinConfig(runningAsJar: Boolean, tempDir: File) {
    companion object {
        // this must match the version information in the build.gradle.kts file
        const val VAADIN_VERSION = "14.7.1"

        val EXTRACT_JAR = "extract.jar"
        val EXTRACT_JAR_OVERWRITE = "extract.jar.overwrite"
        val DEBUG = "debug"
    }

    val tokenFileName: String
    val devMode: Boolean
    val pNpmEnabled: Boolean

    // option to extract files or to load from jar only. This is a performance option.
    val extractFromJar: Boolean

    // option to force files to be overwritten when `extractFromJar` is true
    val forceExtractOverwrite: Boolean

    // option to permit us to fully debug the vaadin application. This must be set during the token compile phase
    val debug: Boolean

    init {
        // find the config/stats.json to see what mode (PRODUCTION or DEV) we should run in.
        // we COULD just check the existence of this file...
        //   HOWEVER if we are testing a different configuration from our IDE, this method will not work...
        var tokenJson: JsonObject? = null

        val defaultTokenFile = "VAADIN/${FrontendUtils.TOKEN_FILE}"
        // token location if we are running in a jar
        val tokenInJar = this.javaClass.classLoader.getResource("META-INF/resources/$defaultTokenFile")
        if (tokenInJar != null) {
            tokenFileName = if (runningAsJar) {
                // the token file name MUST always be from disk! This is hard coded, because later we copy out
                // this file from the jar to the temp location.
                File(tempDir, defaultTokenFile).absolutePath
            } else {
                if (tokenInJar.path.startsWith("/")) {
                    tokenInJar.path.substring(1)
                } else {
                    tokenInJar.path
                }
            }

            tokenJson = JsonUtil.parse(tokenInJar.readText(Charsets.UTF_8)) as JsonObject?
        } else {
            // maybe the token file is in the temp build location (used during dev work).
            val devTokenFile = File("build").resolve("resources").resolve("main").resolve("META-INF").resolve("resources").resolve("VAADIN").resolve(
                FrontendUtils.TOKEN_FILE)
            if (devTokenFile.canRead()) {
                tokenFileName = devTokenFile.absoluteFile.normalize().path
                tokenJson = JsonUtil.parse(File(tokenFileName).readText(Charsets.UTF_8)) as JsonObject?
            }
            else {
                tokenFileName = ""
            }
        }

        if (tokenFileName.isEmpty() || tokenJson == null || !tokenJson.hasKey(InitParameters.SERVLET_PARAMETER_PRODUCTION_MODE)) {
            // this is a problem! we must configure the system first via gradle!
            throw java.lang.RuntimeException("Unable to continue! Error reading token!" +
                    "You must FIRST compile the vaadin resources for DEV or PRODUCTION mode!")
        }

        devMode = !tokenJson.getBoolean(InitParameters.SERVLET_PARAMETER_PRODUCTION_MODE)
        pNpmEnabled = tokenJson.getBoolean(InitParameters.SERVLET_PARAMETER_ENABLE_PNPM)
        extractFromJar = getBoolean(tokenJson, EXTRACT_JAR)
        forceExtractOverwrite = getBoolean(tokenJson, EXTRACT_JAR_OVERWRITE, false)
        debug = getBoolean(tokenJson, EXTRACT_JAR_OVERWRITE, false)

        if (devMode && runningAsJar) {
            throw RuntimeException("Invalid run configuration. It is not possible to run DEV MODE from a deployed jar.\n" +
                    "Something is severely wrong!")
        }

        // we are ALWAYS running in full Vaadin14 mode
        System.setProperty(Constants.VAADIN_PREFIX + InitParameters.SERVLET_PARAMETER_COMPATIBILITY_MODE, "false")

        if (devMode) {
            // set the location of our frontend dir + generated dir when in dev mode
            System.setProperty(FrontendUtils.PARAM_FRONTEND_DIR, tokenJson.getString(Constants.FRONTEND_TOKEN))
            System.setProperty(FrontendUtils.PARAM_GENERATED_DIR, tokenJson.getString(Constants.GENERATED_TOKEN))
        }
    }

    fun getBoolean(tokenJson: JsonObject, tokenName: String, defaultValue: Boolean = true): Boolean {
        return if (tokenJson.hasKey(tokenName)) tokenJson.getBoolean(tokenName) else defaultValue
    }

    fun addServletInitParameters(servlet: ServletInfo) {
        servlet
            .addInitParam("productionMode", (!devMode).toString()) // this is set via the gradle build

            // have to say where our token file lives
            .addInitParam(FrontendUtils.PARAM_TOKEN_FILE, tokenFileName)
    }

    fun setupStatsJsonUrl(servlet: ServletInfo, url: String) {
        // load the stats file as a URL, NOT via the classloader!
        // this is because in java9+, the classpath/module "mess" complicates the accessibility of this file.

        // the stats.json http request will be coming from the local box (so everything is local. Only when on a specific IP should that specific IP be used)
        servlet
            .addInitParam(InitParameters.EXTERNAL_STATS_FILE, "true")
            .addInitParam(InitParameters.EXTERNAL_STATS_URL, "$url/VAADIN/config/stats.json")
    }
}
