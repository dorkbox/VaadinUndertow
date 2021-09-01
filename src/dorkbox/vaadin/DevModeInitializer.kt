/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package dorkbox.vaadin

import com.vaadin.flow.component.WebComponentExporter
import com.vaadin.flow.component.WebComponentExporterFactory
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.dependency.JavaScript
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.component.dependency.NpmPackage
import com.vaadin.flow.function.DeploymentConfiguration
import com.vaadin.flow.router.HasErrorParameter
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.*
import com.vaadin.flow.server.frontend.FrontendUtils
import com.vaadin.flow.server.frontend.NodeTasks
import com.vaadin.flow.server.startup.ClassLoaderAwareServletContainerInitializer
import com.vaadin.flow.server.startup.ServletDeployer.StubServletConfig
import com.vaadin.flow.theme.NoTheme
import com.vaadin.flow.theme.Theme
import elemental.json.Json
import elemental.json.JsonObject
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.*
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.servlet.*
import javax.servlet.annotation.HandlesTypes
import javax.servlet.annotation.WebListener

/**
 * THIS IS COPIED DIRECTLY FROM VAADIN 14.6.8 (flow 2.4.6)
 *
 * CHANGES FROM DEFAULT ARE MANAGED AS DIFFERENT REVISIONS.
 *
 * The initial commit is exactly as-is from vaadin.
 *
 * This file is NOT extensible/configurable AT-ALL, so this is required...
 *
 *
 * Servlet initializer starting node updaters as well as the webpack-dev-mode
 * server.
 *
 * @since 2.0
 */
@HandlesTypes(
    Route::class,
    UIInitListener::class,
    VaadinServiceInitListener::class,
    WebComponentExporter::class,
    WebComponentExporterFactory::class,
    NpmPackage::class,
    NpmPackage.Container::class,
    JsModule::class,
    JsModule.Container::class,
    CssImport::class,
    CssImport.Container::class,
    JavaScript::class,
    JavaScript.Container::class,
    Theme::class,
    NoTheme::class,
    HasErrorParameter::class
)
@WebListener
class DevModeInitializer : ClassLoaderAwareServletContainerInitializer, Serializable, ServletContextListener {
    companion object {
        private val JAR_FILE_REGEX = Pattern.compile(".*file:(.+\\.jar).*")

        // Path of jar files in a URL with zip protocol doesn't start with "zip:"
        // nor "file:". It contains only the path of the file.
        // Weblogic uses zip protocol.
        private val ZIP_PROTOCOL_JAR_FILE_REGEX = Pattern.compile("(.+\\.jar).*")
        private val VFS_FILE_REGEX = Pattern.compile("(vfs:/.+\\.jar).*")
        private val VFS_DIRECTORY_REGEX = Pattern.compile("vfs:/.+")

        // allow trailing slash
        private val DIR_REGEX_FRONTEND_DEFAULT = Pattern.compile("^(?:file:0)?(.+)" + Constants.RESOURCES_FRONTEND_DEFAULT + "/?$")

        // allow trailing slash
        private val DIR_REGEX_COMPATIBILITY_FRONTEND_DEFAULT = Pattern.compile(
            "^(?:file:)?(.+)"
                    + Constants.COMPATIBILITY_RESOURCES_FRONTEND_DEFAULT
                    + "/?$"
        )

        /**
         * Initialize the devmode server if not in production mode or compatibility
         * mode.
         *
         * @param classes
         * classes to check for npm- and js modules
         * @param context
         * servlet context we are running in
         * @param config
         * deployment configuration
         *
         * @throws ServletException
         * if dev mode can't be initialized
         */
        @Throws(ServletException::class)
        fun initDevModeHandler(classes: Set<Class<*>?>?, context: ServletContext?, config: DeploymentConfiguration) {
            System.err.println("CUSTOM INIT!")
            if (config.isProductionMode) {
                log().debug("Skipping DEV MODE because PRODUCTION MODE is set.")
                return
            }
            if (config.isCompatibilityMode) {
                log().debug("Skipping DEV MODE because BOWER MODE is set.")
                return
            }
            if (!config.enableDevServer()) {
                log().debug("Skipping DEV MODE because dev server shouldn't be enabled.")
                return
            }


            val baseDir = config.getStringProperty(FrontendUtils.PROJECT_BASEDIR, null) ?: baseDirectoryFallback
            val generatedDir = System.getProperty(FrontendUtils.PARAM_GENERATED_DIR, FrontendUtils.DEFAULT_GENERATED_DIR)

            val frontendFolder = config.getStringProperty(
                FrontendUtils.PARAM_FRONTEND_DIR,
                System.getProperty(FrontendUtils.PARAM_FRONTEND_DIR, FrontendUtils.DEFAULT_FRONTEND_DIR))

            val builder = NodeTasks.Builder(DevModeClassFinder(classes), File(baseDir), File(generatedDir), File(frontendFolder))

            log().info("Starting dev-mode updaters in {} folder.", builder.npmFolder)
            if (!builder.generatedFolder.exists()) {
                try {
                    FileUtils.forceMkdir(builder.generatedFolder)
                } catch (e: IOException) {
                    throw UncheckedIOException(
                        String.format(
                            "Failed to create directory '%s'",
                            builder.generatedFolder
                        ),
                        e
                    )
                }
            }
            val generatedPackages = File(builder.generatedFolder, Constants.PACKAGE_JSON)

            // If we are missing the generated webpack configuration then generate
            // webpack configurations
            if (!File(builder.npmFolder, FrontendUtils.WEBPACK_GENERATED).exists()) {
                builder.withWebpack(builder.npmFolder, FrontendUtils.WEBPACK_CONFIG, FrontendUtils.WEBPACK_GENERATED)
            }

            // If we are missing either the base or generated package json files
            // generate those
            if (!File(builder.npmFolder, Constants.PACKAGE_JSON).exists() || !generatedPackages.exists()) {
                builder.createMissingPackageJson(true)
            }

            val frontendLocations = getFrontendLocationsFromClassloader(DevModeInitializer::class.java.classLoader)
            val useByteCodeScanner = config.getBooleanProperty(
                InitParameters.SERVLET_PARAMETER_DEVMODE_OPTIMIZE_BUNDLE,
                java.lang.Boolean.parseBoolean(
                    System.getProperty(
                        InitParameters.SERVLET_PARAMETER_DEVMODE_OPTIMIZE_BUNDLE,
                        java.lang.Boolean.FALSE.toString()
                    )
                )
            )

            val enablePnpm = config.isPnpmEnabled
            val useHomeNodeExec = config.getBooleanProperty(InitParameters.REQUIRE_HOME_NODE_EXECUTABLE, false)
            val vaadinContext: VaadinContext = VaadinServletContext(context)
            val tokenFileData = Json.createObject()

            try {
                builder.enablePackagesUpdate(true)
                    .useByteCodeScanner(useByteCodeScanner)
                    .copyResources(frontendLocations)
                    .copyLocalResources(File(baseDir, Constants.LOCAL_FRONTEND_RESOURCES_PATH))
                    .enableImportsUpdate(true).runNpmInstall(true)
                    .populateTokenFileData(tokenFileData)
                    .withEmbeddableWebComponents(true).enablePnpm(enablePnpm)
                    .withHomeNodeExecRequired(useHomeNodeExec).build()
                    .execute()

                val chunk = FrontendUtils.readFallbackChunk(tokenFileData)
                if (chunk != null) {
                    vaadinContext.setAttribute(chunk)
                }
            } catch (exception: ExecutionFailedException) {
                log().debug("Could not initialize dev mode handler. One of the node tasks failed", exception)
                throw ServletException(exception)
            }
            val tasks = builder.enablePackagesUpdate(true)
                .useByteCodeScanner(useByteCodeScanner)
                .copyResources(frontendLocations)
                .copyLocalResources(File(baseDir, Constants.LOCAL_FRONTEND_RESOURCES_PATH))
                .enableImportsUpdate(true).runNpmInstall(true)
                .populateTokenFileData(tokenFileData)
                .withEmbeddableWebComponents(true).enablePnpm(enablePnpm)
                .withHomeNodeExecRequired(useHomeNodeExec).build()

            // Check whether executor is provided by the caller (framework)
            val service = config.initParameters[Executor::class.java]
            val runnable = Runnable {
                runNodeTasks(
                    vaadinContext, tokenFileData,
                    tasks
                )
            }

            val nodeTasksFuture =
                if (service is Executor) {
                    // if there is an executor use it to run the task
                    CompletableFuture.runAsync(
                        runnable,
                        service as Executor?
                    )
                } else {
                    CompletableFuture.runAsync(runnable)
                }

            DevModeHandler.start(config, builder.npmFolder, nodeTasksFuture)
        }

        private fun log(): Logger {
            return LoggerFactory.getLogger(DevModeInitializer::class.java)
        }

        /*
         * Accept user.dir or cwd as a fallback only if the directory seems to be a
         * Maven or Gradle project. Check to avoid cluttering server directories
         * (see tickets #8249, #8403).
         */
        private val baseDirectoryFallback: String
            get() {
                val baseDirCandidate = System.getProperty("user.dir", ".")
                val path = Paths.get(baseDirCandidate)
                return if (path.toFile().isDirectory
                    && (path.resolve("pom.xml").toFile().exists()
                            || path.resolve("build.gradle").toFile().exists())
                ) {
                    path.toString()
                } else {
                    throw IllegalStateException(
                        String.format(
                            "Failed to determine project directory for dev mode. "
                                    + "Directory '%s' does not look like a Maven or "
                                    + "Gradle project. Ensure that you have run the "
                                    + "prepare-frontend Maven goal, which generates "
                                    + "'flow-build-info.json', prior to deploying your "
                                    + "application",
                            path.toString()
                        )
                    )
                }
            }

        /*
         * This method returns all folders of jar files having files in the
         * META-INF/resources/frontend folder. We don't use URLClassLoader because
         * will fail in Java 9+
         */
        @Throws(ServletException::class)
        fun getFrontendLocationsFromClassloader(classLoader: ClassLoader): Set<File> {
            val frontendFiles: MutableSet<File> = HashSet()
            frontendFiles.addAll(
                getFrontendLocationsFromClassloader(
                    classLoader,
                    Constants.RESOURCES_FRONTEND_DEFAULT
                )
            )
            frontendFiles.addAll(
                getFrontendLocationsFromClassloader(
                    classLoader,
                    Constants.COMPATIBILITY_RESOURCES_FRONTEND_DEFAULT
                )
            )
            return frontendFiles
        }


        private fun runNodeTasks(vaadinContext: VaadinContext, tokenFileData: JsonObject, tasks: NodeTasks) {
            try {
                tasks.execute()
                val chunk = FrontendUtils.readFallbackChunk(tokenFileData)
                if (chunk != null) {
                    vaadinContext.setAttribute(chunk)
                }
            } catch (exception: ExecutionFailedException) {
                log().debug("Could not initialize dev mode handler. One of the node tasks failed", exception)
                throw CompletionException(exception)
            }
        }

        @Throws(ServletException::class)
        private fun getFrontendLocationsFromClassloader(classLoader: ClassLoader, resourcesFolder: String): Set<File> {
            val frontendFiles: MutableSet<File> = HashSet()
            try {
                val en = classLoader.getResources(resourcesFolder) ?: return frontendFiles
                val vfsJars: MutableSet<String> = HashSet()
                while (en.hasMoreElements()) {
                    val url = en.nextElement()
                    val urlString = url.toString()
                    val path = URLDecoder.decode(url.path, StandardCharsets.UTF_8.name())

                    val jarMatcher = JAR_FILE_REGEX.matcher(path)
                    val zipProtocolJarMatcher = ZIP_PROTOCOL_JAR_FILE_REGEX.matcher(path)
                    val dirMatcher = DIR_REGEX_FRONTEND_DEFAULT.matcher(path)
                    val dirCompatibilityMatcher = DIR_REGEX_COMPATIBILITY_FRONTEND_DEFAULT.matcher(path)
                    val jarVfsMatcher = VFS_FILE_REGEX.matcher(urlString)
                    val dirVfsMatcher = VFS_DIRECTORY_REGEX.matcher(urlString)

                    if (jarVfsMatcher.find()) {
                        val vfsJar = jarVfsMatcher.group(1)
                        if (vfsJars.add(vfsJar)) frontendFiles.add(
                            getPhysicalFileOfJBossVfsJar(URL(vfsJar))
                        )
                    } else if (dirVfsMatcher.find()) {
                        val vfsDirUrl = URL(
                            urlString.substring(0,urlString.lastIndexOf(resourcesFolder)
                            )
                        )
                        frontendFiles.add(getPhysicalFileOfJBossVfsDirectory(vfsDirUrl))
                    } else if (jarMatcher.find()) {
                        frontendFiles.add(File(jarMatcher.group(1)))
                    } else if ("zip".equals(url.protocol, ignoreCase = true) && zipProtocolJarMatcher.find()
                    ) {
                        frontendFiles.add(File(zipProtocolJarMatcher.group(1)))
                    } else if (dirMatcher.find()) {
                        frontendFiles.add(File(dirMatcher.group(1)))
                    } else if (dirCompatibilityMatcher.find()) {
                        frontendFiles.add(File(dirCompatibilityMatcher.group(1)))
                    } else {
                        log().warn("Resource {} not visited because does not meet supported formats.", url.path)
                    }
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
            return frontendFiles
        }

        @Throws(IOException::class, ServletException::class)
        private fun getPhysicalFileOfJBossVfsDirectory(url: URL): File {
            return try {
                val virtualFile = url.openConnection().content
                val virtualFileClass: Class<*> = virtualFile.javaClass

                // Reflection as we cannot afford a dependency to WildFly or JBoss
                val getChildrenRecursivelyMethod = virtualFileClass.getMethod("getChildrenRecursively")
                val getPhysicalFileMethod = virtualFileClass.getMethod("getPhysicalFile")

                // By calling getPhysicalFile, we make sure that the corresponding
                // physical files/directories of the root directory and its children
                // are created. Later, these physical files are scanned to collect
                // their resources.
                val virtualFiles = getChildrenRecursivelyMethod.invoke(virtualFile) as List<*>
                val rootDirectory = getPhysicalFileMethod.invoke(virtualFile) as File
                for (child in virtualFiles) {
                    // side effect: create real-world files
                    getPhysicalFileMethod.invoke(child)
                }
                rootDirectory
            } catch (exc: NoSuchMethodException) {
                throw ServletException("Failed to invoke JBoss VFS API.", exc)
            } catch (exc: IllegalAccessException) {
                throw ServletException("Failed to invoke JBoss VFS API.", exc)
            } catch (exc: InvocationTargetException) {
                throw ServletException("Failed to invoke JBoss VFS API.", exc)
            }
        }

        @Throws(IOException::class, ServletException::class)
        private fun getPhysicalFileOfJBossVfsJar(url: URL): File {
            return try {
                val jarVirtualFile = url.openConnection().content

                // Creating a temporary jar file out of the vfs files
                val vfsJarPath = url.toString()
                val fileNamePrefix = vfsJarPath.substring(vfsJarPath.lastIndexOf('/') + 1, vfsJarPath.lastIndexOf(".jar"))
                val tempJar = Files.createTempFile(fileNamePrefix, ".jar")
                generateJarFromJBossVfsFolder(jarVirtualFile, tempJar)

                val tempJarFile = tempJar.toFile()
                tempJarFile.deleteOnExit()
                tempJarFile
            } catch (exc: NoSuchMethodException) {
                throw ServletException("Failed to invoke JBoss VFS API.", exc)
            } catch (exc: IllegalAccessException) {
                throw ServletException("Failed to invoke JBoss VFS API.", exc)
            } catch (exc: InvocationTargetException) {
                throw ServletException("Failed to invoke JBoss VFS API.", exc)
            }
        }

        @Throws(IOException::class, IllegalAccessException::class, InvocationTargetException::class, NoSuchMethodException::class)
        private fun generateJarFromJBossVfsFolder(jarVirtualFile: Any, tempJar: Path) {
            // We should use reflection to use JBoss VFS API as we cannot afford a
            // dependency to WildFly or JBoss
            val virtualFileClass: Class<*> = jarVirtualFile.javaClass
            val getChildrenRecursivelyMethod = virtualFileClass.getMethod("getChildrenRecursively")
            val openStreamMethod = virtualFileClass.getMethod("openStream")
            val isFileMethod = virtualFileClass.getMethod("isFile")
            val getPathNameRelativeToMethod = virtualFileClass.getMethod("getPathNameRelativeTo", virtualFileClass)
            val jarVirtualChildren = getChildrenRecursivelyMethod.invoke(jarVirtualFile) as List<*>
            ZipOutputStream(Files.newOutputStream(tempJar)).use { zipOutputStream ->
                for (child in jarVirtualChildren) {
                    if (!(isFileMethod.invoke(child) as Boolean)) continue

                    val relativePath = getPathNameRelativeToMethod.invoke(child, jarVirtualFile) as String

                    val inputStream = openStreamMethod.invoke(child) as InputStream

                    val zipEntry = ZipEntry(relativePath)
                    zipOutputStream.putNextEntry(zipEntry)
                    IOUtils.copy(inputStream, zipOutputStream)
                    zipOutputStream.closeEntry()
                }
            }
        }
    }


    @Throws(ServletException::class)
    override fun process(classes: Set<Class<*>?>?, context: ServletContext) {
        val registrations: Collection<ServletRegistration> = context.servletRegistrations.values
        var vaadinServletRegistration: ServletRegistration? = null

        for (registration in registrations) {
            try {
                if (registration.className != null && isVaadinServletSubClass(registration.className)) {
                    vaadinServletRegistration = registration
                    break
                }
            } catch (e: ClassNotFoundException) {
                throw ServletException(String.format("Servlet class name (%s) can't be found!", registration.className), e)
            }
        }

        val config =
        if (vaadinServletRegistration != null) {
            StubServletConfig.createDeploymentConfiguration(context, vaadinServletRegistration, VaadinServlet::class.java)
        } else {
            StubServletConfig.createDeploymentConfiguration(context, VaadinServlet::class.java)
        }

        initDevModeHandler(classes, context, config)
    }

    @Throws(ClassNotFoundException::class)
    private fun isVaadinServletSubClass(className: String): Boolean {
        return VaadinServlet::class.java.isAssignableFrom(Class.forName(className))
    }

    override fun contextInitialized(ctx: ServletContextEvent) {
        // No need to do anything on init
    }

    override fun contextDestroyed(ctx: ServletContextEvent) {
        val handler = DevModeHandler.getDevModeHandler()
        if (handler != null && !handler.reuseDevServer()) {
            handler.stop()
        }
    }
}
