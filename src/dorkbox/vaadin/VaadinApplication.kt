package dorkbox.vaadin

import com.vaadin.flow.server.VaadinServlet
import com.vaadin.flow.server.frontend.FrontendUtils
import dorkbox.vaadin.devMode.DevModeInitializer
import dorkbox.vaadin.undertow.*
import dorkbox.vaadin.util.CallingClass
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
import io.undertow.servlet.api.ServletContainerInitializerInfo
import io.undertow.servlet.api.ServletInfo
import io.undertow.servlet.api.ServletSessionConfig
import io.undertow.websockets.jsr.WebSocketDeploymentInfo
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.xnio.Xnio
import org.xnio.XnioWorker
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.servlet.Servlet
import javax.servlet.ServletContainerInitializer
import javax.servlet.SessionTrackingMode
import javax.servlet.annotation.HandlesTypes
import kotlin.reflect.KClass


/**
 * Loads, Configures, and Starts a Vaadin 14 application
 */
class VaadinApplication() {
    companion object {
        const val debugResources = false

        /**
         * Gets the version number.
         */
        const val version = "14.0"

        init {
            // Add this project to the updates system, which verifies this class + UUID + version information
            dorkbox.updates.Updates.add(VaadinApplication::class.java, "fc74a52b08c8410fabfea67ac5dca566", version)
        }
    }

    private val logger = KotlinLogging.logger {}

    val runningAsJar: Boolean
    val tempDir: File = File(System.getProperty("java.io.tmpdir", "tmpDir"), "undertow").absoluteFile

    private val onStopList = mutableListOf<Runnable>()

    val vaadinConfig: VaadinConfig

    private lateinit var urlClassLoader: URLClassLoader
    private lateinit var resourceCollectionManager: ResourceCollectionManager

    private val resources = ArrayList<ResourceManager>()

    private val exactResources = ArrayList<String>()
    private val prefixResources = ArrayList<String>()

    private lateinit var cacheHandler: HttpHandler
    private lateinit var servletHttpHandler: HttpHandler

    @Volatile
    private var undertowServer: Undertow? = null

    init {
        // THIS code might be as a jar, however we want to test if the **TOP** leve; code that called this is running as a jar.
        runningAsJar = CallingClass.get().getResource("")!!.protocol == "jar"


        vaadinConfig = VaadinConfig(runningAsJar, tempDir)
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

//                // we only care about VAADIN and frontend resources for JARs -- everything else is compiled as part of the webpack process.
//                val lower = relativePath.lowercase(Locale.US)
//                if (!lower.startsWith("vaadin") && !lower.startsWith("frontend")) {
//                    logger.trace { "Skipping classpath resource: $relativePath" }
//                    return@forEach
//                }

                logger.debug { "Jar resource: $relativePath"  }
                if (lastFile != resource.classpathElementFile) {
                    lastFile = resource.classpathElementFile
                    logger.debug { "Jar resource: ${resource.classpathElementFile}"  }
                }

                // we should copy this resource out, since loading resources from jar files is time+memory intensive
                val outputFile = tempDir.resolve(relativePath)

//                // TODO: should overwrite file? check hashes?
                // if there is ever a NEW version of our code run, the OLD version will still run if the files are not overwritten!
//                if (!outputFile.exists()) {
                    val parentFile = outputFile.parentFile
                    if (!parentFile.isDirectory && !parentFile.mkdirs()) {
                        logger.debug { "Unable to create output directory $parentFile" }
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

//                // we only care about VAADIN and frontend resources for JARs -- everything else is compiled as part of the webpack process.
//                val lower = relativePath.lowercase(Locale.US)
//                if (!lower.startsWith("vaadin") && !lower.startsWith("frontend")) {
//                    logger.trace { "Skipping JAR resource: $relativePath" }
//                    return@forEach
//                }

                logger.debug { "Jar resource: $relativePath" }
                if (lastFile != resource.classpathElementFile) {
                    lastFile = resource.classpathElementFile
                    logger.debug { "Jar resource: ${resource.classpathElementFile}"  }
                }

                // these are all resources inside JAR files.
                jarLocations.add(WebResourceString(relativePath, resource.classpathElementURL, resourcePath))

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

                logger.debug { "Jar resource: $relativePath" }
                if (lastFile != resource.classpathElementFile) {
                    lastFile = resource.classpathElementFile
                    logger.debug { "Jar resource: ${resource.classpathElementFile}"  }
                }

                // these are all resources inside JAR files.
                jarLocations.add(WebResourceString(relativePath, resource.url, resourcePath))

                // jar file this resource is from -- BUT NOT THE RESOURCE ITSELF
                urlClassLoader.add(resource.classpathElementURL)
            }

            // some static resources from disk are ALSO loaded by the classloader.
            scanResultLocalDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)

                logger.debug { "Local resource: $relativePath" }

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
        val jarStringTrie = DoubleArrayTrie(jarStringResourceRequestMap)
        val jarUrlTrie = DoubleArrayTrie(jarUrlResourceRequestMap)
        val diskTrie = DoubleArrayTrie(diskResourceRequestMap)

                                                                                    //URL Classloader: META-INF/VAADIN/build/vaadin-bundle-4d7dbedf0dba552475bc.cache.js
        // so we can use the undertow cache to serve resources, instead of the vaadin servlet (which doesn't cache, and is really slow)
        // NOTE: this will load the stats.json file!
        val toTypedArray = jarLocations.map { it.resourcePath }.toTypedArray()
        this.urlClassLoader = object : URLClassLoader(toTypedArray, this.javaClass.classLoader) {
            override fun getResource(name: String): URL? {
                if (debugResources) {
                    println(" URL Classloader: $name")
                }

                // check disk first
                val diskResourcePath: URL? = diskTrie[name]
                if (diskResourcePath != null) {
                    if (debugResources) {
                        println("TRIE: $diskResourcePath")
                    }
                    return diskResourcePath
                }

                val jarResourcePath: String? = jarStringTrie[name]
                if (jarResourcePath != null) {
                    if (debugResources) {
                        println("TRIE: $jarResourcePath")
                    }
                    return super.getResource(jarResourcePath)
                }

                return super.getResource(name)
            }
        }

        val strictFileResourceManager = StrictFileResourceManager("Static Files", diskTrie)
        val jarResourceManager = JarResourceManager("Jar Files", jarUrlTrie)

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
                    servletClass: Class<out Servlet> = VaadinServlet::class.java,
                    servletName: String = "Vaadin",
                    servletConfig: ServletInfo.() -> Unit = {},
                    undertowConfig: Undertow.Builder.() -> Unit) {


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


        val servlet = Servlets.servlet(servletName, servletClass)
            .setLoadOnStartup(1)
            .setAsyncSupported(true)
            .setExecutor(null) // we use coroutines!

            // have to say where our NPM/dev mode files live.
            .addInitParam(FrontendUtils.PROJECT_BASEDIR, File("").absolutePath)

            .addInitParam("enable-websockets", "true")
            .addMapping("/*")

        vaadinConfig.addServletInitParameters(servlet)


        // setup (or change) custom config options (
        servletConfig(servlet)

        val servletBuilder = Servlets.deployment()
                .setClassLoader(urlClassLoader)
                .setResourceManager(conditionalResourceManager)
                .setDisplayName(servletName)
                .setDefaultEncoding("UTF-8")
                .setSecurityDisabled(true) // security is controlled in memory using vaadin
                .setContextPath("/") // root context path
                .setDeploymentName(servletName)
                .addServlets(servlet)
                .setSessionPersistenceManager(FileSessionPersistence(tempDir))
                .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME, WebSocketDeploymentInfo())
            .addServletContainerInitializers(listOf())

        val sessionCookieName = ServletSessionConfig.DEFAULT_SESSION_ID

        // use the created actor
        // FIXME: 8 actors and 2 threads concurrency on the actor map?
        val coroutineHttpWrapper = CoroutineHttpWrapper(sessionCookieName, 8, 2)

        onStopList.add(Runnable {
            // launch new coroutine in background and continue, since we want to stop our http wrapper in a different coroutine!
            GlobalScope.launch {
                coroutineHttpWrapper.stop()
            }
        })

        servletBuilder.initialHandlerChainWrappers.add(coroutineHttpWrapper)

        // destroy the actors on session invalidation
        servletBuilder.addSessionListener(ActorSessionCleanup(coroutineHttpWrapper.actorsPerSession))

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


        /////////////////////////////////////////////////////////////////
        // INITIALIZING AND STARTING THE SERVLET
        /////////////////////////////////////////////////////////////////
        val manager = Servlets.defaultContainer().addDeployment(servletBuilder)
        manager.deploy()

        // TODO: adjust the session timeout (default is 30 minutes) from when the LAST heartbeat is detected
        // manager.deployment.sessionManager.setDefaultSessionTimeout(TimeUnit.MINUTES.toSeconds(Args.webserver.sessionTimeout).toInt())
        servletHttpHandler = manager.start()





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


        val serverBuilder: Undertow.Builder = Undertow.builder()
            // Max 1 because we immediately hand off to a coroutine handler
            .setWorkerThreads(1)
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

        undertowServer = serverBuilder.build()
    }

    fun handleRequest(exchange: HttpServerExchange) {
        // dev-mode : incoming requests USUALLY start with a '/'
        val path = exchange.relativePath
        if (debugResources) {
            println("REQUEST undertow: $path")
        }

        // serve the following directly via the resource handler, so we can do it directly in the networking IO thread.
        // Because this is non-blocking, this is also the preferred way to do this for performance.
        // at the time of writing, this was "/icons", "/images", and in production mode "/VAADIN"
        exactResources.forEach {
            if (path == it) {
                cacheHandler.handleRequest(exchange)
                return
            }
        }

        prefixResources.forEach {
            if (path.startsWith(it)) {
                if (it == "/VAADIN" &&
                    // Dynamic resources need to be handled by the default handler, not the cacheHandler.
                    path[8] == 'd' && path[9] == 'y' && path[10] == 'n') {
                        servletHttpHandler.handleRequest(exchange)
                        return
                }

                cacheHandler.handleRequest(exchange)
                return
            }
        }

        // this is the default, and will use coroutines + servlet to handle the request
        servletHttpHandler.handleRequest(exchange)
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

    fun start() {
        val threadGroup = ThreadGroup("Undertow Web Server")

        // NOTE: we start this in a NEW THREAD so we can create and use a thread-group for all of the undertow threads created. This allows
        //  us to keep our main thread group "un-cluttered" when analyzing thread/stack traces.
        //
        //  This is a hacky, but undertow does not support setting the thread group in the builder.

        val exceptionThrown = AtomicReference<Exception>()
        val latch = CountDownLatch(1)

        Thread(threadGroup) {
            try {
                undertowServer?.start()
            } catch (e: Exception) {
                exceptionThrown.set(e)
            } finally {
                latch.countDown()
            }
        }.start()

        latch.await()

        val exception = exceptionThrown.get()
        if (exception != null) {
            throw exception
        }
    }

    fun stop() {
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
}
