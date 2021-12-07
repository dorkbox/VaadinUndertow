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
package dorkbox.vaadin

import com.vaadin.flow.server.frontend.FrontendUtils
import dorkbox.vaadin.devMode.DevModeInitializer
import dorkbox.vaadin.undertow.*
import dorkbox.vaadin.util.CallingClass
import dorkbox.vaadin.util.TrieClassLoader
import dorkbox.vaadin.util.UndertowBuilder
import dorkbox.vaadin.util.VaadinConfig
import dorkbox.vaadin.util.ahoCorasick.DoubleArrayTrie
import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import io.undertow.Undertow
import io.undertow.UndertowMessages
import io.undertow.UndertowOptions
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.cache.CacheHandler
import io.undertow.server.handlers.cache.DirectBufferCache
import io.undertow.server.handlers.resource.CachingResourceManager
import io.undertow.server.handlers.resource.ResourceManager
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.*
import io.undertow.websockets.jsr.WebSocketDeploymentInfo
import mu.KotlinLogging
import org.xnio.Xnio
import org.xnio.XnioWorker
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.servlet.*
import javax.servlet.annotation.HandlesTypes
import kotlin.reflect.KClass


/**
 * Loads, Configures, and Starts a Vaadin 14 application
 */
@Suppress("unused")
class VaadinApplication : ExceptionHandler {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "14.7.2"

        const val vaadinVersion = VaadinConfig.VAADIN_VERSION

        init {
            // Add this project to the updates system, which verifies this class + UUID + version information
            dorkbox.updates.Updates.add(VaadinApplication::class.java, "fc74a52b08c8410fabfea67ac5dca566", version)
        }
    }

    private val logger = KotlinLogging.logger {}
    private val httpLogger = KotlinLogging.logger(logger.name + ".http")

    val runningAsJar: Boolean
    val tempDir: File = File(System.getProperty("java.io.tmpdir", "tmpDir"), "undertow").absoluteFile

    private val onStopList = mutableListOf<Runnable>()

    val vaadinConfig: VaadinConfig

    private lateinit var trieClassLoader: TrieClassLoader
    private lateinit var resourceCollectionManager: ResourceCollectionManager

    private val resources = ArrayList<ResourceManager>()

//    private val exactResources = ArrayList<String>()
//    private val prefixResources = ArrayList<String>()

    private val originalClassLoader: ClassLoader



    private lateinit var cacheHandler: HttpHandler
    private lateinit var servletHttpHandler: HttpHandler
    private lateinit var servletManager: DeploymentManager

    private lateinit var jarStringTrie: DoubleArrayTrie<String>
    private lateinit var jarUrlTrie: DoubleArrayTrie<URL>
    private lateinit var diskTrie: DoubleArrayTrie<URL>

    private lateinit var servletBuilder: DeploymentInfo
    private lateinit var serverBuilder: UndertowBuilder
    private lateinit var servlet: ServletInfo

    /** This url is used to define what the base url is for accessing the Vaadin stats.json file */
    lateinit var baseUrl: String


    @Volatile
    private var undertowServer: Undertow? = null

    init {
        // THIS code might be as a jar, however we want to test if the **TOP** leve; code that called this is running as a jar.
        runningAsJar = CallingClass.get().getResource("")!!.protocol == "jar"

        vaadinConfig = VaadinConfig(runningAsJar, tempDir)
        originalClassLoader = Thread.currentThread().contextClassLoader
    }

    private fun addAnnotated(annotationScanner: ScanResult,
                             kClass: KClass<*>,
                             classSet: MutableSet<Class<*>>) {
        val javaClass = kClass.java
        val canonicalName = javaClass.canonicalName

        when {
            javaClass.isAnnotation -> {
                val routes = annotationScanner.getClassesWithAnnotation(canonicalName)
                val loadedClasses = routes.loadClasses()

                classSet.addAll(loadedClasses)
            }
            javaClass.isInterface  -> {
                val classesImplementing = annotationScanner.getClassesImplementing(canonicalName)
                val loadedClasses = classesImplementing.loadClasses()

                classSet.addAll(loadedClasses)
            }
            kClass.isAbstract      -> { /* do nothing! */ }
            else                   -> throw RuntimeException("Annotation scan for type $canonicalName:$javaClass not supported yet")
        }
    }

    private fun recurseAllFiles(allRelativeFilePaths: MutableSet<WebResource>, file: File, rootFileSize: Int) {
        file.listFiles()?.forEach {
            if (it.isDirectory) {
                recurseAllFiles(allRelativeFilePaths, it, rootFileSize)
            } else {
                val resourcePath = it.toURI().toURL()

                // must ALSO use forward slash (unix)!!
                val relativePath = resourcePath.path.substring(rootFileSize)

                logger.error {
                    "Disk resource: $relativePath"
                }

                allRelativeFilePaths.add(WebResource(relativePath, resourcePath))
            }
        }
    }

    @Suppress("DuplicatedCode")
    fun initResources() {
        val metaInfResources = "META-INF/resources"
        val metaInfValLength = metaInfResources.length + 1

        val buildMetaInfResources = "build/resources/main/META-INF/resources"


        // resource locations are tricky...
        // when a JAR  : META-INF/resources
        // when on disk: webApp/META-INF/resources

        // TODO: check if the modules restriction (see following note) is still the case for vaadin 14
        // NOTE: we cannot use "modules" yet (so no module-info.java file...) otherwise every dependency gets added to the module path,
        //    and since almost NONE of them support modules, this will break us.


        // NOTE: we cannot use "modules" yet (so no module-info.java file...) otherwise every dependency gets added to the module path,
        //    and since almost NONE of them support modules, this will break us.
        // find all of the jars in the module/classpath with resources in the META-INF directory
        logger.info { "Discovering all bundled jar $metaInfResources locations" }

        val scanResultJarDependencies = ClassGraph()
                .filterClasspathElements { it.endsWith(".jar") }
                .acceptPaths(metaInfResources)
                .scan()


        val scanResultLocalDependencies = ClassGraph()
                .filterClasspathElements { !it.endsWith(".jar") }
                .acceptPaths(metaInfResources)
                .scan()


        val jarLocations = mutableSetOf<WebResourceString>()
        val diskLocations = mutableSetOf<WebResource>()
        val urlClassLoader = mutableSetOf<URL>()

        if (runningAsJar && vaadinConfig.extractFromJar) {
            // running from JAR (really just temp-dir, since the jar is extracted!)
            logger.info { "Running from jar and extracting all jar [$metaInfResources] files to [$tempDir]" }

            var lastFile: File? = null
            scanResultJarDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)

                logger.trace { "Jar resource: $relativePath"  }
                if (lastFile != resource.classpathElementFile) {
                    lastFile = resource.classpathElementFile
                    logger.trace { "Jar resource: ${resource.classpathElementFile}"  }
                }

                // we should copy this resource out, since loading resources from jar files is time+memory intensive
                val outputFile = tempDir.resolve(relativePath)

//                // TODO: should overwrite file? check hashes?
                // if there is ever a NEW version of our code run, the OLD version will still run if the files are not overwritten!
//                if (!outputFile.exists()) {
                    val parentFile = outputFile.parentFile
                    if (!parentFile.isDirectory && !parentFile.mkdirs()) {
                        logger.trace { "Unable to create output directory $parentFile" }
                    } else {
                        resource.open().use { input ->
                            outputFile.outputStream().use { input.copyTo(it) }
                        }
                    }
//                }

                diskLocations.add(WebResource(relativePath, outputFile.toURI().toURL()))
            }


        } else if (runningAsJar) {
            // running from JAR (not-extracted!)
            logger.info { "Running from jar files" }

            var lastFile: File? = null
            scanResultJarDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)
                val resourceUrl = resource.url

                logger.trace { "Jar resource: $relativePath" }
                if (lastFile != resource.classpathElementFile) {
                    lastFile = resource.classpathElementFile
                    logger.trace { "Jar resource: ${resource.classpathElementFile}"  }
                }

                val path = resourceUrl.path
                val resourceDir = path.substring(0, path.length - relativePath.length)

                // these are all resources inside JAR files.
                jarLocations.add(WebResourceString(relativePath, resource.classpathElementURL, resourcePath, URL(resourceDir)))

                // jar file this resource is from -- BUT NOT THE RESOURCE ITSELF
                urlClassLoader.add(resource.classpathElementURL)
            }


        } else {
            // running from IDE
            logger.info { "Running from IDE files" }

            var lastFile: File? = null
            scanResultJarDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)
                val resourceUrl = resource.url

                logger.trace { "Jar resource: $relativePath" }
                if (lastFile != resource.classpathElementFile) {
                    lastFile = resource.classpathElementFile
                    logger.trace { "Jar resource: ${resource.classpathElementFile}"  }
                }

                val path = resourceUrl.path
                val resourceDir = path.substring(0, path.length - relativePath.length)

                // these are all resources inside JAR files.
                jarLocations.add(WebResourceString(relativePath, resourceUrl, resourcePath, URL(resourceDir)))

                // jar file this resource is from -- BUT NOT THE RESOURCE ITSELF
                urlClassLoader.add(resource.classpathElementURL)
            }

            // some static resources from disk are ALSO loaded by the classloader.
            scanResultLocalDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)

                logger.trace { "Local resource: $relativePath" }

                diskLocations.add(WebResource(relativePath, resource.url))
            }

            // we also have resources that are OUTSIDE the classpath (ie: in the temp build dir)
            // This is necessary BECAUSE we have to be able to ALSO serve resources via the classloader!
            val buildDirMetaInfResources = File(buildMetaInfResources).absoluteFile.normalize()
            val rootPathLength = buildDirMetaInfResources.toURI().toURL().path.length
            recurseAllFiles(diskLocations, buildDirMetaInfResources, rootPathLength)
        }

        // we use the TRIE data structure to QUICKLY find what we are looking for.
        // This is so our classloader can find the resource without having to manually configure each request.
        val jarStringResourceRequestMap = mutableMapOf<String, String>()
        val jarUrlResourceRequestMap = mutableMapOf<String, URL>()
        val diskResourceRequestMap = mutableMapOf<String, URL>()

        jarLocations.forEach { (requestPath, resourcePath, relativeResourcePath) ->
            // make sure the path is WWW request compatible (ie: no spaces/etc)
            val wwwCompatiblePath = java.net.URLDecoder.decode(requestPath, Charsets.UTF_8)

            // this adds the resource to our request map, used by our trie
            jarStringResourceRequestMap[wwwCompatiblePath] = relativeResourcePath
            jarUrlResourceRequestMap[wwwCompatiblePath] = resourcePath

            if (!wwwCompatiblePath.startsWith("META-INF")) {
                // some-of the resources are loaded with a "META-INF" prefix by the vaadin servlet
                jarStringResourceRequestMap["META-INF/$wwwCompatiblePath"] = relativeResourcePath
                jarUrlResourceRequestMap["META-INF/$wwwCompatiblePath"] = resourcePath
            }

            if (!wwwCompatiblePath.startsWith('/')) {
                // some-of the resources are loaded with a "/" prefix by the vaadin servlet
                jarStringResourceRequestMap["/$wwwCompatiblePath"] = relativeResourcePath
                jarUrlResourceRequestMap["/$wwwCompatiblePath"] = resourcePath
            }
        }

        diskLocations.forEach { (requestPath, resourcePath) ->
            // make sure the path is WWW request compatible (ie: no spaces/etc)
            val wwwCompatiblePath = java.net.URLDecoder.decode(requestPath, Charsets.UTF_8)

            // this adds the resource to our request map, used by our trie
            diskResourceRequestMap[wwwCompatiblePath] = resourcePath

            if (!wwwCompatiblePath.startsWith("META-INF")) {
                // some-of the resources are loaded with a "META-INF" prefix by the vaadin servlet
                diskResourceRequestMap["META-INF/$wwwCompatiblePath"] = resourcePath
            }

            if (!wwwCompatiblePath.startsWith('/')) {
                // some-of the resources are loaded with a "/" prefix by the vaadin servlet
                diskResourceRequestMap["/$wwwCompatiblePath"] = resourcePath
            }
        }

        // EVERYTHING IS ACCESSED VIA TRIE, NOT VIA HASHMAP! (it's faster this way)
        jarStringTrie = DoubleArrayTrie(jarStringResourceRequestMap)
        jarUrlTrie = DoubleArrayTrie(jarUrlResourceRequestMap)
        diskTrie = DoubleArrayTrie(diskResourceRequestMap)


        // so we can use the undertow cache to serve resources, instead of the vaadin servlet (which doesn't cache, and is really slow)
        // NOTE: this will load the stats.json file!

        val debug = vaadinConfig.debug

        // for our classloader, we have to make sure that we are BY DIRECTORY, not by file, for the resource array!
        val toTypedArray = jarLocations.map { it.resourceDir }.toSet().toTypedArray()
        this.trieClassLoader = TrieClassLoader(diskTrie, jarStringTrie, toTypedArray, this.javaClass.classLoader, debug)

        // we want to start ALL aspects of the application using our NEW classloader (instead of the "current" classloader)
        Thread.currentThread().contextClassLoader = this.trieClassLoader

        val strictFileResourceManager = StrictFileResourceManager("Static Files", diskTrie, debug)
        val jarResourceManager = JarResourceManager("Jar Files", jarUrlTrie, debug)

//        val jarResources = ArrayList<JarResourceManager>()
//        jarLocations.forEach { (requestPath, resourcePath, relativeResourcePath) ->
////            val cleanedUrl = java.net.URLDecoder.decode(jarUrl.file, Charsets.UTF_8)
//            val file = File(resourcePath.file)
//
//            if (debugResources) {
//                println(" JAR: $file")
//            }
//
//            // the location IN THE JAR is actually "META-INF/resources", so we want to make sure of that when
//            // serving the request, that the correct path is used.
//            jarResources.add(JarResourceManager(file, metaInfResources))
//        }


        //  collect all the resources available from each location to ALSO be handled by undertow
//        val diskResources = ArrayList<FileResourceManager>()
//        val fileResources = ArrayList<FileResourceManager>()





//        diskLocations.forEach { (requestPath, resourcePath) ->
//            val wwwCompatiblePath = java.net.URLDecoder.decode(requestPath, Charsets.UTF_8)
//            val diskFile = resourcePath.file
//
//
//            // this serves a BASE location!
//            diskResources.add(FileResourceManager(metaInfResourcesLocation))
//
//            // if this location is where our "META-INF/resources" directory exists, ALSO add that location, because the
//            // vaadin will request resources based on THAT location as well.
//            val metaInfResourcesLocation = File(file, metaInfResources)
//
//            if (metaInfResourcesLocation.isDirectory) {
//                diskResources.add(FileResourceManager(metaInfResourcesLocation))
//
//                // we will also serve content from ALL child directories
//                metaInfResourcesLocation.listFiles()?.forEach { childFile ->
//                    val element = "/${childFile.relativeTo(metaInfResourcesLocation)}"
//                    if (debugResources) {
//                        println(" DISK: $cleanedUrl")
//                    }
//
//                    when {
//                        childFile.isDirectory -> prefixResources.add(element)
//                        else -> exactResources.add(element)
//                    }
//                }
//            }
//
//            if (debugResources) {
//                println(" DISK: $cleanedUrl")
//            }
//
//            diskResources.add(FileResourceManager(file))
//
//            // we will also serve content from ALL child directories
//            //   (except for the META-INF dir, which we are ALREADY serving content)
//            file.listFiles()?.forEach { childFile ->
//                val element = "/${childFile.relativeTo(file)}"
//
//                if (debugResources) {
//                    println(" DISK: $element")
//                }
//
//                when {
//                    childFile.isDirectory -> {
//                        if (childFile.name != "META-INF") {
//                            prefixResources.add(element)
//                        }
//                    }
//                    else -> exactResources.add(element)
//                }
//            }
//        }
//        jarLocations.forEach { jarUrl ->
//            val cleanedUrl = java.net.URLDecoder.decode(jarUrl.file, Charsets.UTF_8)
//            val file = File(cleanedUrl)
//
//            if (debugResources) {
//                println(" JAR: $cleanedUrl")
//            }
//
//            // the location IN THE JAR is actually "META-INF/resources", so we want to make sure of that when
//            // serving the request, that the correct path is used.
//            jarResources.add(JarResourceManager(file, metaInfResources))
//        }

        // When we are searching for resources, the following search order is optimized for access speed and request hit order
        //   DISK
        //   files
        //   jars (since they are containers)
        //     flow-client
        //     flow-push
        //     flow-server
        //     then every other jar
        resources.add(strictFileResourceManager)
        resources.add(jarResourceManager)
//        resources.addAll(diskResources)
//        resources.addAll(fileResources)


//        val client = jarResources.firstOrNull { it.name.contains("flow-client") }
//        val push = jarResources.firstOrNull { it.name.contains("flow-push") }
//        val server = jarResources.firstOrNull { it.name.contains("flow-server") }
//
//        if (client != null && push != null && server != null) {
//            // these jars will ALWAYS be available (as of Vaadin 14.2)
//            // if we are running from a fatjar, then the resources will likely be extracted (so this is not necessary)
//            jarResources.remove(client)
//            jarResources.remove(push)
//            jarResources.remove(server)
//
//            resources.add(client)
//            resources.add(push)
//            resources.add(server)
//        }
//
//        resources.addAll(jarResources)

        // TODO: Have a 404 resource handler to log when a requested file is not found!

        // NOTE: atmosphere is requesting the full path of 'WEB-INF/classes/'.
        //   What to do? search this with classgraph OR we re-map this to 'out/production/classes/' ??
        //   also accessed is : WEB-INF/lib/

        // TODO: runtime GZ compression of resources!?! only necessary in the JAR run mode (which is what runs on servers)
    }

    fun initServlet(enableCachedHandlers: Boolean, cacheTimeoutSeconds: Int,
                    servletClass: Class<out Servlet> = com.vaadin.flow.server.VaadinServlet::class.java,
                    servletName: String = "Vaadin",
                    servletConfig: ServletInfo.() -> Unit = {},
                    undertowConfig: UndertowBuilder.() -> Unit) {


        resourceCollectionManager = ResourceCollectionManager(resources)

        val conditionalResourceManager =
                when {
                    enableCachedHandlers -> {
                        val cacheSize = 1024 // size of the cache
                        val maxFileSize = 1024*1024*1024*10L // 10 mb file. The biggest file size we cache
                        val maxFileAge = TimeUnit.HOURS.toMillis(1) // How long an item can stay in the cache in milliseconds
                        val bufferCache = DirectBufferCache(1024, 10, 1024 * 1024 * 200)
                        CachingResourceManager(cacheSize, maxFileSize, bufferCache, resourceCollectionManager, maxFileAge.toInt())
                    }
                    else -> {
                        // sometimes it is really hard to debug when using the cache
                        resourceCollectionManager
                    }
                }

        onStopList.add(Runnable {
            conditionalResourceManager.close()
        })



        // directly serve our static requests in the IO thread (and not in a worker/coroutine)
        val staticResourceHandler = DirectResourceHandler(resourceCollectionManager)
        if (enableCachedHandlers) {
            staticResourceHandler.setCacheTime(cacheTimeoutSeconds)  // tell the browser to cache our static resources (in seconds)
        }

        cacheHandler = when {
            enableCachedHandlers -> {
                val cache = DirectBufferCache(1024, 10, 1024 * 1024 * 200)
                CacheHandler(cache, staticResourceHandler)
            }
            else -> {
                // sometimes it is really hard to debug when using the cache
                staticResourceHandler
            }
        }




//        servletClass: Class<out Servlet> = VaadinServlet::class.java,
        // we have to load the instance of the VaadinServlet INSIDE our url handler! (so all traffic/requests go through the url classloader!)
//        val forceReloadClassLoader = object : ClassLoader(trieClassLoader) {
//            override fun loadClass(name: String?, resolve: Boolean): Class<*> {
//                if (name == servletClassName) {
//                    throw ClassNotFoundException()
////                    return trieClassLoader.findClass(name)
//                }
//
//                return super.loadClass(name, resolve)
//            }
//        }
//
//        val forceReloadClassLoader2 = object : ClassLoader(forceReloadClassLoader) { }

//        val servletClass = Class.forName(servletClassName, true, trieClassLoader) as Class<out Servlet>
//        val cl = servletClass.classLoader

//        val instance = servletClass.constructors[0].newInstance()
//        val immediateInstanceFactory = ImmediateInstanceFactory(instance) as ImmediateInstanceFactory<out Servlet>

        val threadGroup = ThreadGroup("Web Server")
        val executor = Executors.newCachedThreadPool(DaemonThreadFactory("HttpWrapper", threadGroup))


        servlet = Servlets.servlet(servletName, servletClass)
            .setLoadOnStartup(1)
            .setAsyncSupported(true)
            .setExecutor(executor)

            // have to say where our NPM/dev mode files live.
            .addInitParam(FrontendUtils.PROJECT_BASEDIR, File("").absolutePath)

            .addInitParam("enable-websockets", "true")
            .addMapping("/*")

        vaadinConfig.addServletInitParameters(servlet)


        // setup (or change) custom config options (
        servletConfig(servlet)

        servletBuilder = Servlets.deployment()
            .setClassLoader(trieClassLoader)
            .setResourceManager(conditionalResourceManager)
            .setDisplayName(servletName)
            .setDefaultEncoding("UTF-8")
            .setSecurityDisabled(true) // security is controlled in memory using vaadin
            .setContextPath("/") // root context path
            .setDeploymentName(servletName)
            .setExceptionHandler(this)
            .addServlets(servlet)
            .setSessionPersistenceManager(FileSessionPersistence(tempDir))
            .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME, WebSocketDeploymentInfo())
            .addServletContainerInitializers(listOf())

        val sessionCookieName = ServletSessionConfig.DEFAULT_SESSION_ID

//        // use the created actor
//        // FIXME: 8 actors and 2 threads concurrency on the actor map?
//        val coroutineHttpWrapper = CoroutineHttpWrapper(sessionCookieName, 8, 2)
//
//        onStopList.add(Runnable {
//            // launch new coroutine in background and continue, since we want to stop our http wrapper in a different coroutine!
//            GlobalScope.launch {
//                coroutineHttpWrapper.stop()
//            }
//        })
//
//        servletBuilder.initialHandlerChainWrappers.add(coroutineHttpWrapper)
//
//        // destroy the actors on session invalidation
//        servletBuilder.addSessionListener(ActorSessionCleanup(coroutineHttpWrapper.actorsPerSession))



        // configure how the servlet behaves
        val servletSessionConfig = ServletSessionConfig()
//        servletSessionConfig.isSecure = true  // cookies are only possible when via HTTPS
        servletSessionConfig.sessionTrackingModes = setOf(SessionTrackingMode.COOKIE)
        servletSessionConfig.name = sessionCookieName
        servletBuilder.servletSessionConfig = servletSessionConfig


        // Regardless of metadata, if there are any ServletContainerInitializers with @HandlesTypes, we must add the classes
        // Next, scan all these classes so we can call their onStartup() methods correctly
        val serviceLoader = ServiceLoader.load(ServletContainerInitializer::class.java)

        ClassGraph().enableAnnotationInfo().enableClassInfo().scan().use { annotationScanner ->
            for (service in serviceLoader) {
                val classSet: HashSet<Class<*>> = hashSetOf()
                val javaClass = service.javaClass
                val annotation= javaClass.getAnnotation(HandlesTypes::class.java)

                if (annotation != null) {
                    val classes = annotation.value
                    for (aClass in classes) {
                        addAnnotated(annotationScanner, aClass, classSet)
                    }
                }

                if (classSet.isNotEmpty()) {
                    if (javaClass == com.vaadin.flow.server.startup.DevModeInitializer::class.java) {
                        if (vaadinConfig.devMode) {
                            // instead of the default, we load **OUR** dev-mode initializer.
                            // The vaadin one is super buggy for custom environments
                            servletBuilder.addServletContainerInitializer(
                                ServletContainerInitializerInfo(DevModeInitializer::class.java, classSet))
                        }
                    } else {
                        // do not load the dev-mode initializer for production mode
                        servletBuilder.addServletContainerInitializer(ServletContainerInitializerInfo(javaClass, classSet))
                    }
                }
            }
        }

        if (vaadinConfig.devMode) {
            // NOTE: The vaadin flow files only exist AFTER vaadin is initialized, so this block MUST be after 'manager.deploy()'
            // in dev mode, the local resources are hardcoded to an **INCORRECT** location. They
            // are hardcoded to  "src/main/resources/META-INF/resources/frontend", so we have to
            // copy them ourselves from the correct location... ( node_modules/@vaadin/flow-frontend/ )
            val targetDir = File("build", FrontendUtils.NODE_MODULES + FrontendUtils.FLOW_NPM_PACKAGE_NAME).absoluteFile
            logger.info { "Copying local frontend resources to $targetDir" }
            if (!targetDir.exists()) {
                throw RuntimeException("Startup directories are missing! Unable to continue - please run compileResources for DEV mode!")
            }

            File("frontend").absoluteFile.copyRecursively(targetDir, true)
        }

        serverBuilder = UndertowBuilder()
            .setSocketOption(org.xnio.Options.BACKLOG, 10000)

            // Let the server workers have time to close when we shutdown
            .setServerOption(UndertowOptions.SHUTDOWN_TIMEOUT, 10000)

            .setSocketOption(org.xnio.Options.REUSE_ADDRESSES, true)

            // In HTTP/1.1, connections are persistent unless declared otherwise.
            // Adding a "Connection: keep-alive" header to every response would only add useless bytes.
            .setServerOption(UndertowOptions.SSL_USER_CIPHER_SUITES_ORDER, true)
            .setServerOption(UndertowOptions.ENABLE_STATISTICS, false)

        // configure or override options
        undertowConfig(serverBuilder)



        // setup the base URL from the server builder
        val (isHttps, host, port) = serverBuilder.httpListener
        val transport = if (isHttps) {
            "https://"
        } else {
            "http://"
        }

        val hostInfo = if (host == "0.0.0.0") {
            "127.0.0.1"
        } else {
            host
        }

        val portInfo = when {
            isHttps && port == 443 -> ""
            !isHttps && port == 80 -> ""
            else -> ":$port"
        }

        baseUrl = "$transport$hostInfo$portInfo"
    }

    ////
    ////
    //// undertow server specific methods
    ////
    ////


    val xnio: Xnio?
        get() {
            return undertowServer?.xnio
        }

    val worker: XnioWorker?
        get() {
            return undertowServer?.worker
        }

    val listenerInfo: MutableList<Undertow.ListenerInfo>
        get() {
            return undertowServer?.listenerInfo ?: throw UndertowMessages.MESSAGES.serverNotStarted()
        }

    @Throws(IOException::class)
    fun start() {
        logger.info("Starting version $version")
        logger.info("Starting Vaadin $vaadinVersion")

        // make sure that the stats.json file is accessible
        // the request will come in as 'VAADIN/config/stats.json' or '/VAADIN/config/stats.json'
        //
        // If stats.json DOES NOT EXIST, there will be infinite recursive lookups for this file.
        val statsFile = "VAADIN/config/stats.json"

        // our resource manager ONLY manages disk + jars!
        if (diskTrie[statsFile] == null && jarStringTrie[statsFile] == null) {
            throw IOException("Unable to startup the VAADIN webserver. The 'stats.json' definition file is not available.  (Usually at '$statsFile'')" )
        }

        val statsUrl = "$baseUrl/$statsFile"
        logger.info("Setting up stats file http location as: $statsUrl")
        vaadinConfig.setupStatsJsonUrl(servlet, statsUrl)

        undertowServer = serverBuilder.build()

        /////////////////////////////////////////////////////////////////
        // INITIALIZING AND STARTING THE SERVLET
        /////////////////////////////////////////////////////////////////
        servletManager = Servlets.defaultContainer().addDeployment(servletBuilder)
        servletManager.deploy()


        // TODO: adjust the session timeout (default is 30 minutes) from when the LAST heartbeat is detected
        // manager.deployment.sessionManager.setDefaultSessionTimeout(TimeUnit.MINUTES.toSeconds(Args.webserver.sessionTimeout).toInt())
        servletHttpHandler = servletManager.start()


        // NOTE: look into SessionRestoringHandler to keep session state across re-deploys (this is normally not used in production). this might just be tricks with classloaders to keep sessions around
        // we also want to save sessions to disk, and be able to read from them if we want See InMemorySessionManager (we would have to write our own)

        /*
         * look at the following
         * GracefulShutdownHandler
         * LearningPushHandler
         * RedirectHandler
         * RequestLimitingHandler
         * SecureCookieHandler
         *
         *
         * to setup ALPN and ssl, it's FASTER to use openssl instead of java
         * http://wildfly.org/news/2017/10/06/OpenSSL-Support-In-Wildfly/
         * https://github.com/undertow-io/undertow/blob/master/core/src/main/java/io/undertow/protocols/alpn/OpenSSLAlpnProvider.java
         */


        val threadGroup = ThreadGroup("Undertow Web Server")

        // NOTE: we start this in a NEW THREAD so we can create and use a thread-group for all of the undertow threads created. This allows
        //  us to keep our main thread group "un-cluttered" when analyzing thread/stack traces.
        //
        //  This is a hacky, but undertow does not support setting the thread group in the builder.

        val exceptionThrown = AtomicReference<Exception>()
        val latch = CountDownLatch(1)

        val thread = Thread(threadGroup) {
            try {
                undertowServer?.start()
            } catch (e: Exception) {
                exceptionThrown.set(e)
            } finally {
                latch.countDown()
            }
        }
        thread.contextClassLoader = this.trieClassLoader
        thread.start()

        latch.await()

        Thread.currentThread().contextClassLoader = this.originalClassLoader

        val exception = exceptionThrown.get()
        if (exception != null) {
            throw exception
        }
    }

    fun stop() {
        try {
            servletManager.stop()
        } catch (e: Exception) {
            // ignored
        }

        try {
            // servletBridge.shutdown();
            // serverChannel.close().awaitUninterruptibly();
            // bootstrap.releaseExternalResources();
            //                servletWebapp.destroy()
            //                allChannels.close().awaitUninterruptibly()

            val worker = worker
            if (worker != null) {
                worker.shutdown()
                worker.awaitTermination(10L, TimeUnit.SECONDS)
            }

            undertowServer?.stop()
        } finally {
            onStopList.forEach {
                it.run()
            }
        }
    }

    fun handleRequest(exchange: HttpServerExchange) {
        // dev-mode : incoming requests USUALLY start with a '/'
        val path = exchange.relativePath

        httpLogger.trace { "REQUEST undertow: $path" }

        if (path.length == 1) {
            httpLogger.trace { "REQUEST of length 1: $path" }
            servletHttpHandler.handleRequest(exchange)
            return
        }

        // serve the following directly via the resource handler, so we can do it directly in the networking IO thread.
        // Because this is non-blocking, this is also the preferred way to do this for performance.
        // at the time of writing, this was "/icons", "/images", and in production mode "/VAADIN"

        // our resource manager ONLY manages disk + jars!
        val diskResourcePath: URL? = diskTrie[path]
        if (diskResourcePath != null) {
            httpLogger.trace { "URL DISK TRIE: $diskResourcePath" }

            cacheHandler.handleRequest(exchange)
            return
        }

        val jarResourcePath: String? = jarStringTrie[path]
        if (jarResourcePath != null) {
            httpLogger.trace { "URL JAR TRIE: $jarResourcePath" }
            cacheHandler.handleRequest(exchange)
            return
        }

        // this is the default, and will use the servlet to handle the request
        httpLogger.trace { "Forwarding request to servlet" }

        servletHttpHandler.handleRequest(exchange)
    }

    /**
     * Handles an exception. If this method returns true then the request/response cycle is considered to be finished,
     * and no further action will take place, if this returns false then standard error page redirect will take place.
     *
     * The default implementation of this simply logs the exception and returns false, allowing error page and async context
     * error handling to proceed as normal.
     *
     * @param exchange        The exchange
     * @param request         The request
     * @param response        The response
     * @param throwable       The exception
     * @return <code>true</code> true if the error was handled by this method
     */
    override fun handleThrowable(
        exchange: HttpServerExchange?,
        request: ServletRequest?,
        response: ServletResponse?,
        throwable: Throwable?
    ): Boolean {
        logger.error("Error ${request} : ${response}", throwable)
        return false
    }
}
