package dorkbox.vaadin

import com.vaadin.flow.server.Constants
import com.vaadin.flow.server.InitParameters
import com.vaadin.flow.server.VaadinServlet
import com.vaadin.flow.server.frontend.FrontendUtils
import dorkbox.vaadin.devMode.DevModeInitializer
import dorkbox.vaadin.undertow.*
import dorkbox.vaadin.util.CallingClass
import dorkbox.vaadin.util.ahoCorasick.DoubleArrayTrie
import elemental.json.JsonObject
import elemental.json.impl.JsonUtil
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
import io.undertow.server.handlers.resource.FileResourceManager
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
        /**
         * Gets the version number.
         */
        const val version = "0.1"

        init {
            // Add this project to the updates system, which verifies this class + UUID + version information
            dorkbox.updates.Updates.add(VaadinApplication::class.java, "fc74a52b08c8410fabfea67ac5dca566", version)
        }
    }

    val runningAsJar: Boolean
    private val logger = KotlinLogging.logger {}
    val tempDir: File = File(System.getProperty("java.io.tmpdir", "tmpDir"), "undertow").absoluteFile

    private val onStopList = mutableListOf<Runnable>()

    val devMode: Boolean
    private val tokenFileName: String
    val pNpmEnabled: Boolean

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
            val devTokenFile = File("build").resolve("resources").resolve("main").resolve("META-INF").resolve("resources").resolve("VAADIN").resolve(FrontendUtils.TOKEN_FILE)
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

    @Suppress("DuplicatedCode")
    fun initResources() {
        val metaInfResources = "META-INF/resources"
        val buildMetaInfResources = "build/resources/main/META-INF/resources"
        val metaInfValLength = metaInfResources.length + 1


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


        val jarLocations = mutableSetOf<URL>()
        val diskLocations = mutableSetOf<URL>()

        if (runningAsJar) {
            // collect all the resources available.
            logger.info { "Extracting all jar $metaInfResources files to $tempDir" }

            scanResultJarDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)

                logger.trace {
                    "Discovered resource: $relativePath"
                }

                // we should copy this resource out, since loading resources from jar files is time+memory intensive
                val outputFile = File(tempDir, relativePath)

                if (!outputFile.exists()) {
                    val parentFile = outputFile.parentFile
                    if (!parentFile.isDirectory && !parentFile.mkdirs()) {
                        logger.error { "Unable to create output directory $parentFile" }
                    } else {
                        resource.open().use { input ->
                            outputFile.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }

            val locations = arrayOf(tempDir.toURI().toURL())
            jarLocations.addAll(locations)

            // so we can use the undertow cache to serve resources, instead of the vaadin servlet (which doesn't cache, and is really slow)
            urlClassLoader = object : URLClassLoader(locations, this.javaClass.classLoader) {
                override fun getResource(name: String): URL? {
                    if (name.startsWith("META-INF")) {
                        // the problem is that:
                        //   request is :  META-INF/VAADIN/build/webcomponentsjs/webcomponents-loader.js
                        //   resource is:  VAADIN/build/webcomponentsjs/webcomponents-loader.js

                        val fixedName = name.substring("META-INF".length)
                        return super.getResource(fixedName)
                    }

                    return super.getResource(name)
                }
            }
        }
        else {
            // when we are running in DISK (aka, not-running-as-a-jar) mode, we are NOT extracting all of the resources to a temp location.
            // BECAUSE of this, we must create a MAP of the RELATIVE resource name --> ABSOLUTE resource name
            // This is so our classloader can find the resource without having to manually configure each requests.
            val jarResourceRequestMap = TreeMap<String, String>()
            val diskResourceRequestMap = TreeMap<String, URL>()


            scanResultJarDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)

                logger.error { "Jar resource: $relativePath" }

                jarLocations.add(resource.classpathElementURL)

                jarResourceRequestMap[relativePath] = resourcePath
                // some-of the resources are loaded with a "META-INF" prefix by the vaadin servlet
                jarResourceRequestMap["META-INF/$relativePath"] = resourcePath
            }


            // some static resources from disk are ALSO loaded by the classloader.
            scanResultLocalDependencies.allResources.forEach { resource ->
                val resourcePath = resource.pathRelativeToClasspathElement
                val relativePath = resourcePath.substring(metaInfValLength)

                logger.error { "Classpath resource: $relativePath" }

                diskLocations.add(resource.classpathElementURL)

                val url = resource.url
                diskResourceRequestMap[relativePath] = url

                // some-of the resources are loaded with a "META-INF" prefix by the vaadin servlet
                diskResourceRequestMap["META-INF/$relativePath"] = url
            }

            // we also have resources that are OUTSIDE the classpath (ie: in the temp build dir)
            // This is necessary BECAUSE we have to be able to ALSO serve resources via the classloader!
            val buildDirMetaInfResources = File(buildMetaInfResources).absoluteFile.normalize()
            val rootPathLength = buildDirMetaInfResources.toURI().toURL().path.length
            recurseAllFiles(diskResourceRequestMap, buildDirMetaInfResources, rootPathLength)


            // so we can use the undertow cache to serve resources, instead of the vaadin servlet (which doesn't cache, and is really slow)
            // NOTE: this is what ALSO will load the stats.json file!
            urlClassLoader = object : URLClassLoader(jarLocations.toTypedArray(), this.javaClass.classLoader) {
                val jarTrie = DoubleArrayTrie(jarResourceRequestMap)
                val diskTrie = DoubleArrayTrie(diskResourceRequestMap)

                override fun getResource(name: String): URL? {
                    // check disk first
                    val diskResourcePath = diskTrie[name]
                    if (diskResourcePath != null) {
                        return diskResourcePath
                    }

                    val jarResourcePath = jarTrie[name]
                    if (jarResourcePath != null) {
                        return super.getResource(jarResourcePath)
                    }

                    return super.getResource(name)
                }
            }

            // we also have resources that are OUTSIDE the classpath (ie: in the temp build dir)
            diskLocations.add(buildDirMetaInfResources.toURI().toURL())
        }

        //  collect all the resources available from each location to ALSO be handled by undertow
        val diskResources = ArrayList<FileResourceManager>()
        val jarResources = ArrayList<JarResourceManager>()
        val fileResources = ArrayList<FileResourceManager>()


        jarLocations.forEach { jarUrl ->
            val cleanedUrl = java.net.URLDecoder.decode(jarUrl.file, Charsets.UTF_8)
            val file = File(cleanedUrl)

            // the location IN THE JAR is actually "META-INF/resources", so we want to make sure of that when
            // serving the request, that the correct path is used.
            jarResources.add(JarResourceManager(file, metaInfResources))
        }

        diskLocations.forEach { diskUrl ->
            val cleanedUrl = java.net.URLDecoder.decode(diskUrl.file, Charsets.UTF_8)
            val file = File(cleanedUrl)

            // if this location is where our "META-INF/resources" directory exists, ALSO add that location, because the
            // vaadin will request resources based on THAT location as well.
            val metaInfResourcesLocation = File(file, metaInfResources)
            if (metaInfResourcesLocation.isDirectory) {
                diskResources.add(FileResourceManager(metaInfResourcesLocation))

                // we will also serve content from ALL child directories
                metaInfResourcesLocation.listFiles()?.forEach { childFile ->
                    when {
                        childFile.isDirectory -> prefixResources.add("/${childFile.relativeTo(metaInfResourcesLocation)}")
                        else -> exactResources.add("/${childFile.relativeTo(metaInfResourcesLocation)}")
                    }
                }
            }

            diskResources.add(FileResourceManager(file))

            // we will also serve content from ALL child directories
            //   (except for the META-INF dir, which we are ALREADY serving content)
            file.listFiles()?.forEach { childFile ->
                when {
                    childFile.isDirectory -> {
                        if (childFile.name != "META-INF") {
                            prefixResources.add("/${childFile.relativeTo(file)}")
                        }
                    }
                    else -> exactResources.add("/${childFile.relativeTo(file)}")
                }
            }
        }

        // When we are searching for resources, the following search order is optimized for access speed and request hit order
        //   DISK
        //   files
        //   jars (since they are containers)
        //     flow-client
        //     flow-push
        //     flow-server
        //     then every other jar
        resources.addAll(diskResources)
        resources.addAll(fileResources)

        val client = jarResources.firstOrNull { it.name.contains("flow-client") }
        val push = jarResources.firstOrNull { it.name.contains("flow-push") }
        val server = jarResources.firstOrNull { it.name.contains("flow-server") }

        if (client != null && push != null && server != null) {
            // these jars will ALWAYS be available (as of Vaadin 14.2)
            // if we are running from a fatjar, then the resources will likely be extracted (so this is not necessary)
            jarResources.remove(client)
            jarResources.remove(push)
            jarResources.remove(server)

            resources.add(client)
            resources.add(push)
            resources.add(server)
        }

        resources.addAll(jarResources)

        // TODO: Have a 404 resource handler to log when a requested file is not found!

        // NOTE: atmosphere is requesting the full path of 'WEB-INF/classes/'.
        //   What do to? search this with classgraph OR we re-map this to 'out/production/classes/' ??
        //   also accessed is : WEB-INF/lib/

        // TODO: runtime GZ compression of resources!?! only necessary in the JAR run mode (which is what runs on servers)
    }

    // Does not add to locations!
    private fun recurseAllFiles(
        resourceRequestMap: TreeMap<String, URL>,
        file: File,
        rootFileSize: Int
    ) {
        file.listFiles()?.forEach {
            if (it.isDirectory) {
                recurseAllFiles(resourceRequestMap, it, rootFileSize)
            } else {
                val resourcePath = it.toURI().toURL()

                // must ALSO use forward slash (unix)!!
                val relativePath = resourcePath.path.substring(rootFileSize)

                logger.error {
                    "Disk resource: $relativePath"
                }

                resourceRequestMap[relativePath] = resourcePath
                // some-of the resources are loaded with a "META-INF" prefix by the vaadin servlet
                resourceRequestMap["META-INF/$relativePath"] = resourcePath
            }
        }
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
            .addInitParam("productionMode", (!devMode).toString()) // this is set via the gradle build

            // have to say where our NPM/dev mode files live.
            .addInitParam(FrontendUtils.PROJECT_BASEDIR, File("").absolutePath)

            // have to say where our token file lives
            .addInitParam(FrontendUtils.PARAM_TOKEN_FILE, tokenFileName)

            // where our stats.json file lives. This loads via classloader, not via a file!!
            .addInitParam(InitParameters.SERVLET_PARAMETER_STATISTICS_JSON, "VAADIN/config/stats.json")

            .addInitParam("enable-websockets", "true")
            .addMapping("/*")


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
                val classSet = hashSetOf<Class<*>>()
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
                        if (devMode) {
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


        if (devMode) {
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
        val path = exchange.relativePath

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

            worker?.shutdown() // maybe?
            undertowServer?.stop()
        } finally {
            onStopList.forEach {
                it.run()
            }
        }
    }
}
