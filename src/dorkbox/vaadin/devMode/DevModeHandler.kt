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
package dorkbox.vaadin.devMode

import com.vaadin.flow.function.DeploymentConfiguration
import com.vaadin.flow.internal.BrowserLiveReload
import com.vaadin.flow.internal.Pair
import com.vaadin.flow.server.*
import com.vaadin.flow.server.communication.StreamRequestHandler
import com.vaadin.flow.server.frontend.FrontendTools
import com.vaadin.flow.server.frontend.FrontendUtils
import dorkbox.vaadin.devMode.HandlerHelper.isPathUnsafe
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.*
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.regex.Pattern
import javax.servlet.ServletOutputStream
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

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
 *
 * Handles getting resources from `webpack-dev-server`.
 *
 *
 * This class is meant to be used during developing time. For a production mode
 * site `webpack` generates the static bundles that will be served
 * directly from the servlet (using a default servlet if such exists) or through
 * a stand alone static file server.
 *
 * By default it keeps updated npm dependencies and node imports before running
 * webpack server
 *
 * @since 2.0
 */
class DevModeHandler private constructor(
    config: DeploymentConfiguration, runningPort: Int,
    npmFolder: File, waitFor: CompletableFuture<Void?>
) : RequestHandler {
    private var notified = false

    /**
     * Return webpack console output when a compilation error happened.
     *
     * @return console output if error or null otherwise.
     */
    @Volatile
    var failedOutput: String? = null
        private set
    private val isDevServerFailedToStart = AtomicBoolean()
    /**
     * Get the live reload service instance.
     *
     * @return the live reload instance
     */
    /**
     * Set the live reload service instance.
     *
     * @param liveReload
     * the live reload instance
     */
    @Transient
    var liveReload: BrowserLiveReload? = null

    /**
     * Get the listening port of the 'webpack-dev-server'.
     *
     * @return the listening port of webpack
     */
    @Volatile
    var port: Int
        private set


    private val webpackProcess = AtomicReference<Process>()
    private val reuseDevServer: Boolean
    private val watchDog = AtomicReference<DevServerWatchDog?>()
    private var devServerStartFuture: CompletableFuture<Void?>? = null
    private val npmFolder: File
    private val lock = ReentrantReadWriteLock()



    @Throws(IOException::class)
    override fun handleRequest(
        session: VaadinSession, request: VaadinRequest,
        response: VaadinResponse
    ): Boolean {
        return if (devServerStartFuture!!.isDone) {
            try {
                devServerStartFuture!!.getNow(null)
            } catch (exception: CompletionException) {
                isDevServerFailedToStart.set(true)
                throw getCause(exception)
            }
            false
        } else {
            val inputStream = DevModeHandler::class.java
                .getResourceAsStream("dev-mode-not-ready.html")
            IOUtils.copy(inputStream, response.outputStream)
            response.setContentType("text/html;charset=utf-8")
            true
        }
    }

    private fun getCause(exception: Throwable?): RuntimeException {
        return if (exception is CompletionException) {
            getCause(exception.cause)
        } else if (exception is RuntimeException) {
            exception
        } else {
            IllegalStateException(exception)
        }
    }

    /**
     * Returns true if it's a request that should be handled by webpack.
     *
     * @param request
     * the servlet request
     * @return true if the request should be forwarded to webpack
     */
    fun isDevModeRequest(request: HttpServletRequest): Boolean {
        val pathInfo = request.pathInfo
        return pathInfo != null && pathInfo.matches(".+\\.js".toRegex()) && !pathInfo
            .startsWith("/" + StreamRequestHandler.DYN_RES_PREFIX)
    }

    /**
     * Serve a file by proxying to webpack.
     *
     *
     * Note: it considers the [HttpServletRequest.getPathInfo] that will
     * be the path passed to the 'webpack-dev-server' which is running in the
     * context root folder of the application.
     *
     *
     * Method returns `false` immediately if dev server failed on its
     * startup.
     *
     * @param request
     * the servlet request
     * @param response
     * the servlet response
     * @return false if webpack returned a not found, true otherwise
     * @throws IOException
     * in the case something went wrong like connection refused
     */
    @Throws(IOException::class)
    fun serveDevModeRequest(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): Boolean {
        // Do not serve requests if dev server starting or failed to start.
        if (isDevServerFailedToStart.get() || !devServerStartFuture!!.isDone) {
            return false
        }
        // Since we have 'publicPath=/VAADIN/' in webpack config,
        // a valid request for webpack-dev-server should start with '/VAADIN/'
        val requestFilename = request.pathInfo
        if (isPathUnsafe(requestFilename)) {
            logger.info(
                "Blocked attempt to access file: {}",
                requestFilename
            )
            response.status = HttpServletResponse.SC_FORBIDDEN
            return true
        }
        val connection = prepareConnection(
            requestFilename,
            request.method
        )

        // Copies all the headers from the original request
        val headerNames = request.headerNames
        while (headerNames.hasMoreElements()) {
            val header = headerNames.nextElement()
            connection.setRequestProperty(
                header,  // Exclude keep-alive
                if ("Connect" == header) "close" else request.getHeader(header)
            )
        }

        // Send the request
        logger.debug(
            "Requesting resource to webpack {}",
            connection.url
        )
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            logger.debug(
                "Resource not served by webpack {}",
                requestFilename
            )
            // webpack cannot access the resource, return false so as flow can
            // handle it
            return false
        }
        logger.debug(
            "Served resource by webpack: {} {}", responseCode,
            requestFilename
        )

        // Copies response headers
        connection.headerFields.forEach { (header: String?, values: List<String?>) ->
            if (header != null) {
                response.addHeader(header, values[0])
            }
        }
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Copies response payload
            writeStream(
                response.outputStream,
                connection.inputStream
            )
        } else if (responseCode < 400) {
            response.status = responseCode
        } else {
            // Copies response code
            response.sendError(responseCode)
        }

        // Close request to avoid issues in CI and Chrome
        response.outputStream.close()
        return true
    }

    private fun checkWebpackConnection(): Boolean {
        try {
            prepareConnection("/", "GET").responseCode
            return true
        } catch (e: IOException) {
            logger.debug(
                "Error checking webpack dev server connection",
                e
            )
        }
        return false
    }

    /**
     * Prepare a HTTP connection against webpack-dev-server.
     *
     * @param path
     * the file to request
     * @param method
     * the http method to use
     * @return the connection
     * @throws IOException
     * on connection error
     */
    @Throws(IOException::class)
    fun prepareConnection(path: String, method: String?): HttpURLConnection {
        val uri = URL(WEBPACK_HOST + ":" + port + path)
        val connection = uri.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.readTimeout = DEFAULT_TIMEOUT
        connection.connectTimeout = DEFAULT_TIMEOUT
        return connection
    }

    @Synchronized
    private fun doNotify() {
        if (!notified) {
            notified = true
            (this as Object).notifyAll() // NOSONAR
        }
    }

    // mirrors a stream to logger, and check whether a success or error pattern
    // is found in the output.
    private fun logStream(
        input: InputStream, success: Pattern,
        failure: Pattern
    ) {
        val thread = Thread {
            val reader = BufferedReader(
                InputStreamReader(input, StandardCharsets.UTF_8)
            )
            try {
                readLinesLoop(success, failure, reader)
            } catch (e: IOException) {
                if ("Stream closed" == e.message) {
                    FrontendUtils.console(FrontendUtils.GREEN, END)
                    logger.debug(
                        "Exception when reading webpack output.",
                        e
                    )
                } else {
                    logger.error(
                        "Exception when reading webpack output.",
                        e
                    )
                }
            }

            // Process closed stream, means that it exited, notify
            // DevModeHandler to continue
            doNotify()
        }
        thread.isDaemon = true
        thread.name = "webpack"
        thread.start()
    }

    @Throws(IOException::class)
    private fun readLinesLoop(
        success: Pattern, failure: Pattern,
        reader: BufferedReader
    ) {
        var output = outputBuilder
        val info = Consumer { s: String? ->
            logger
                .debug(String.format(FrontendUtils.GREEN, "{}"), s)
        }
        val error = Consumer { s: String? ->
            logger
                .error(String.format(FrontendUtils.RED, "{}"), s)
        }
        val warn = Consumer { s: String? ->
            logger
                .debug(String.format(FrontendUtils.YELLOW, "{}"), s)
        }
        var log = info
        var line: String
        while (reader.readLine().also { line = it } != null) {
            val cleanLine = line // remove color escape codes for console
                .replace("\u001b\\[[;\\d]*m".toRegex(), "") // remove babel query string which is confusing
                .replace("\\?babel-target=[\\w\\d]+".toRegex(), "")

            // write each line read to logger, but selecting its correct level
            log = if (line.contains("WARNING")) warn else if (line.contains("ERROR")) error else if (isInfo(line, cleanLine)) info else log
            log.accept(cleanLine)

            // Only store webpack errors to be shown in the browser.
            if (log == error) {
                // save output so as it can be used to alert user in browser.
                output.append(cleanLine).append(System.lineSeparator())
            }
            val succeed = success.matcher(line).find()
            val failed = failure.matcher(line).find()
            // We found the success or failure pattern in stream
            if (succeed || failed) {
                log.accept(if (succeed) SUCCEED_MSG else FAILED_MSG)
                // save output in case of failure
                failedOutput = if (failed) output.toString() else null
                // reset output and logger for the next compilation
                output = outputBuilder
                log = info
                // Notify DevModeHandler to continue
                doNotify()
            }
        }
    }

    private fun isInfo(line: String, cleanLine: String): Boolean {
        return line.trim { it <= ' ' }.isEmpty() || cleanLine.trim { it <= ' ' }.startsWith("i")
    }

    private val outputBuilder: StringBuilder
        private get() {
            val output = StringBuilder()
            output.append(String.format("Webpack build failed with errors:%n"))
            return output
        }

    @Throws(IOException::class)
    private fun writeStream(
        outputStream: ServletOutputStream,
        inputStream: InputStream
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes: Int
        while (inputStream.read(buffer).also { bytes = it } >= 0) {
            outputStream.write(buffer, 0, bytes)
        }
    }

    /**
     * Remove the running port from the vaadinContext and temporary file.
     */
    fun removeRunningDevServerPort() {
        FileUtils.deleteQuietly(LazyDevServerPortFileInit.DEV_SERVER_PORT_FILE)
    }

    private fun runOnFutureComplete(config: DeploymentConfiguration) {
        try {
            doStartDevModeServer(config)
        } catch (exception: ExecutionFailedException) {
            logger.error(null, exception)
            throw CompletionException(exception)
        }
    }

    private fun saveRunningDevServerPort() {
        val portFile = LazyDevServerPortFileInit.DEV_SERVER_PORT_FILE
        try {
            FileUtils.writeStringToFile(
                portFile, port.toString(),
                StandardCharsets.UTF_8
            )
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    @Throws(ExecutionFailedException::class)
    private fun doStartDevModeServer(config: DeploymentConfiguration) {
        // If port is defined, means that webpack is already running
        if (port > 0) {
            check(checkWebpackConnection()) {
                String.format(
                    "%s webpack-dev-server port '%d' is defined but it's not working properly",
                    START_FAILURE, port
                )
            }
            reuseExistingPort(port)
            return
        }
        port = runningDevServerPort
        if (port > 0) {
            port = if (checkWebpackConnection()) {
                reuseExistingPort(port)
                return
            } else {
                logger.warn(
                    "webpack-dev-server port '%d' is defined but it's not working properly. Using a new free port...",
                    port
                )
                0
            }
        }
        // here the port == 0
        val webPackFiles = validateFiles(npmFolder)
        logger.info("Starting webpack-dev-server")
        watchDog.set(DevServerWatchDog())

        // Look for a free port
        port = freePort
        saveRunningDevServerPort()
        var success = false
        success = try {
            doStartWebpack(config, webPackFiles)
        } finally {
            if (!success) {
                removeRunningDevServerPort()
            }
        }
    }

    private fun doStartWebpack(
        config: DeploymentConfiguration,
        webPackFiles: Pair<File, File>
    ): Boolean {
        val processBuilder = ProcessBuilder()
            .directory(npmFolder)
        val tools = FrontendTools(
            npmFolder.absolutePath
        ) { FrontendUtils.getVaadinHomeDirectory().absolutePath }
        tools.validateNodeAndNpmVersion()
        val useHomeNodeExec = config.getBooleanProperty(
            InitParameters.REQUIRE_HOME_NODE_EXECUTABLE, false
        )
        var nodeExec: String? = null
        nodeExec = if (useHomeNodeExec) {
            tools.forceAlternativeNodeExecutable()
        } else {
            tools.nodeExecutable
        }
        val command = makeCommands(
            config, webPackFiles.first,
            webPackFiles.second, nodeExec
        )
        FrontendUtils.console(FrontendUtils.GREEN, START)
        if (logger.isDebugEnabled) {
            logger.debug(
                FrontendUtils.commandToString(npmFolder.absolutePath, command)
            )
        }
        val start = System.nanoTime()
        processBuilder.command(command)
        try {
            webpackProcess.set(
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                    .redirectErrorStream(true).start()
            )

            // We only can save the webpackProcess reference the first time that
            // the DevModeHandler is created. There is no way to store
            // it in the servlet container, and we do not want to save it in the
            // global JVM.
            // We instruct the JVM to stop the webpack-dev-server daemon when
            // the JVM stops, to avoid leaving daemons running in the system.
            // NOTE: that in the corner case that the JVM crashes or it is
            // killed
            // the daemon will be kept running. But anyways it will also happens
            // if the system was configured to be stop the daemon when the
            // servlet context is destroyed.
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
            val succeed = Pattern.compile(
                config.getStringProperty(
                    InitParameters.SERVLET_PARAMETER_DEVMODE_WEBPACK_SUCCESS_PATTERN,
                    DEFAULT_OUTPUT_PATTERN
                )
            )
            val failure = Pattern.compile(
                config.getStringProperty(
                    InitParameters.SERVLET_PARAMETER_DEVMODE_WEBPACK_ERROR_PATTERN,
                    DEFAULT_ERROR_PATTERN
                )
            )
            logStream(webpackProcess.get().inputStream, succeed, failure)
            logger.info(LOG_START)

            synchronized(this) {
                (this as Object).wait(
                    config.getStringProperty( // NOSONAR
                        InitParameters.SERVLET_PARAMETER_DEVMODE_WEBPACK_TIMEOUT,
                        DEFAULT_TIMEOUT_FOR_PATTERN
                    ).toInt().toLong()
                )
            }
            check(webpackProcess.get().isAlive) { "Webpack exited prematurely" }
            val ms = (System.nanoTime() - start) / 1000000
            logger.info(LOG_END, ms)
            saveRunningDevServerPort()
            return true
        } catch (e: IOException) {
            logger.error("Failed to start the webpack process", e)
        } catch (e: InterruptedException) {
            logger.debug("Webpack process start has been interrupted", e)
        }
        return false
    }

    private fun reuseExistingPort(port: Int) {
        logger.info(
            "Reusing webpack-dev-server running at {}:{}",
            WEBPACK_HOST, port
        )

        // Save running port for next usage
        saveRunningDevServerPort()
        watchDog.set(null)
    }

    private fun makeCommands(
        config: DeploymentConfiguration,
        webpack: File, webpackConfig: File, nodeExec: String?
    ): List<String?> {
        val command: MutableList<String?> = ArrayList()
        command.add(nodeExec)
        command.add(webpack.absolutePath)
        command.add("--config")
        command.add(webpackConfig.absolutePath)
        command.add("--port")
        command.add(port.toString())
        command.add("--watchDogPort=" + watchDog.get()!!.watchDogPort)
        command.addAll(
            Arrays.asList(
                *config
                    .getStringProperty(
                        InitParameters.SERVLET_PARAMETER_DEVMODE_WEBPACK_OPTIONS,
                        "-d --inline=false"
                    )
                    .split(" +").toTypedArray()
            )
        )
        return command
    }

    @Throws(ExecutionFailedException::class)
    private fun validateFiles(npmFolder: File): Pair<File, File> {
        assert(port == 0)
        // Skip checks if we have a webpack-dev-server already running
        val webpack = File(npmFolder, WEBPACK_SERVER)
        val webpackConfig = File(npmFolder, FrontendUtils.WEBPACK_CONFIG)
        if (!npmFolder.exists()) {
            logger.warn("No project folder '{}' exists", npmFolder)
            throw ExecutionFailedException(
                START_FAILURE
                        + " the target execution folder doesn't exist."
            )
        }
        if (!webpack.exists()) {
            logger.warn(
                "'{}' doesn't exist. Did you run `npm install`?",
                webpack
            )
            throw ExecutionFailedException(
                String.format(
                    "%s '%s' doesn't exist. `npm install` has not run or failed.",
                    START_FAILURE, webpack
                )
            )
        } else if (!webpack.canExecute()) {
            logger.warn(
                " '{}' is not an executable. Did you run `npm install`?",
                webpack
            )
            throw ExecutionFailedException(
                String.format(
                    "%s '%s' is not an executable."
                            + " `npm install` has not run or failed.",
                    START_FAILURE, webpack
                )
            )
        }
        if (!webpackConfig.canRead()) {
            logger.warn(
                "Webpack configuration '{}' is not found or is not readable.",
                webpackConfig
            )
            throw ExecutionFailedException(
                String.format(
                    "%s '%s' doesn't exist or is not readable.",
                    START_FAILURE, webpackConfig
                )
            )
        }
        return Pair(webpack, webpackConfig)
    }

    /**
     * Whether the 'webpack-dev-server' should be reused on servlet reload.
     * Default true.
     *
     * @return true in case of reusing the server.
     */
    fun reuseDevServer(): Boolean {
        return reuseDevServer
    }

    /**
     * Stop the webpack-dev-server.
     */
    fun stop() {
        if (atomicHandler.get() == null) {
            return
        }
        try {
            // The most reliable way to stop the webpack-dev-server is
            // by informing webpack to exit. We have implemented in webpack a
            // a listener that handles the stop command via HTTP and exits.
            prepareConnection("/stop", "GET").responseCode
        } catch (e: IOException) {
            logger.debug(
                "webpack-dev-server does not support the `/stop` command.",
                e
            )
        }
        val watchDogInstance = watchDog.get()
        watchDogInstance?.stop()
        val process = webpackProcess.get()
        if (process != null && process.isAlive) {
            process.destroy()
        }
        atomicHandler.set(null)
        removeRunningDevServerPort()
    }

    /**
     * Waits for the dev server to start.
     *
     *
     * Suspends the caller's thread until the dev mode server is started (or
     * failed to start).
     *
     * @see Thread.join
     */
    fun join() {
        devServerStartFuture!!.join()
    }

    private object LazyDevServerPortFileInit {
        val DEV_SERVER_PORT_FILE = createDevServerPortFile()
        private fun createDevServerPortFile(): File {
            return try {
                File.createTempFile("flow-dev-server", "port")
            } catch (exception: IOException) {
                throw UncheckedIOException(exception)
            }
        }
    }

    companion object {
        private const val START_FAILURE = "Couldn't start dev server because"
        private val atomicHandler = AtomicReference<DevModeHandler?>()

        // It's not possible to know whether webpack is ready unless reading output
        // messages. When webpack finishes, it writes either a `Compiled` or a
        // `Failed` in the last line
        private const val DEFAULT_OUTPUT_PATTERN = ": Compiled."
        private const val DEFAULT_ERROR_PATTERN = ": Failed to compile."
        private const val FAILED_MSG = "\n------------------ Frontend compilation failed. -----------------"
        private const val SUCCEED_MSG = "\n----------------- Frontend compiled successfully. -----------------"
        private const val START = "\n------------------ Starting Frontend compilation. ------------------\n"
        private const val END = "\n------------------------- Webpack stopped  -------------------------\n"
        private const val LOG_START = "Running webpack to compile frontend resources. This may take a moment, please stand by..."
        private const val LOG_END = "Started webpack-dev-server. Time: {}ms"

        // If after this time in millisecs, the pattern was not found, we unlock the
        // process and continue. It might happen if webpack changes their output
        // without advise.
        private const val DEFAULT_TIMEOUT_FOR_PATTERN = "60000"
        private const val DEFAULT_BUFFER_SIZE = 32 * 1024
        private const val DEFAULT_TIMEOUT = 120 * 1000
        private const val WEBPACK_HOST = "http://localhost"

        /**
         * The local installation path of the webpack-dev-server node script.
         */
        const val WEBPACK_SERVER = "node_modules/webpack-dev-server/bin/webpack-dev-server.js"

        /**
         * Start the dev mode handler if none has been started yet.
         *
         * @param configuration
         * deployment configuration
         * @param npmFolder
         * folder with npm configuration files
         * @param waitFor
         * a completable future whose execution result needs to be
         * available to start the webpack dev server
         *
         * @return the instance in case everything is alright, null otherwise
         */
        fun start(
            configuration: DeploymentConfiguration,
            npmFolder: File, waitFor: CompletableFuture<Void?>
        ): DevModeHandler? {
            return start(0, configuration, npmFolder, waitFor)
        }

        /**
         * Start the dev mode handler if none has been started yet.
         *
         * @param runningPort
         * port on which Webpack is listening.
         * @param configuration
         * deployment configuration
         * @param npmFolder
         * folder with npm configuration files
         * @param waitFor
         * a completable future whose execution result needs to be
         * available to start the webpack dev server
         *
         * @return the instance in case everything is alright, null otherwise
         */
        fun start(
            runningPort: Int,
            configuration: DeploymentConfiguration, npmFolder: File,
            waitFor: CompletableFuture<Void?>
        ): DevModeHandler? {
            if (configuration.isProductionMode
                || configuration.isCompatibilityMode
                || !configuration.enableDevServer()
            ) {
                return null
            }
            var handler = atomicHandler.get()
            if (handler == null) {
                handler = createInstance(
                    runningPort, configuration, npmFolder,
                    waitFor
                )
                atomicHandler.compareAndSet(null, handler)
            }
            return devModeHandler
        }

        /**
         * Get the instantiated DevModeHandler.
         *
         * @return devModeHandler or `null` if not started
         */
        val devModeHandler: DevModeHandler?
            get() = atomicHandler.get()

        private fun createInstance(
            runningPort: Int,
            configuration: DeploymentConfiguration, npmFolder: File,
            waitFor: CompletableFuture<Void?>
        ): DevModeHandler {
            return DevModeHandler(
                configuration, runningPort, npmFolder,
                waitFor
            )
        }

        // Using an short prefix so as webpack output is more readable
        private val logger: Logger
            private get() = // Using an short prefix so as webpack output is more readable
                LoggerFactory.getLogger("dev-webpack")
        private val runningDevServerPort: Int
            private get() {
                var port = 0
                val portFile = LazyDevServerPortFileInit.DEV_SERVER_PORT_FILE
                if (portFile.canRead()) {
                    try {
                        val portString = FileUtils
                            .readFileToString(portFile, StandardCharsets.UTF_8)
                            .trim { it <= ' ' }
                        if (!portString.isEmpty()) {
                            port = portString.toInt()
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                }
                return port
            }

        /**
         * Returns an available tcp port in the system.
         *
         * @return a port number which is not busy
         */
        val freePort: Int
            get() {
                try {
                    ServerSocket(0).use { s ->
                        s.reuseAddress = true
                        return s.localPort
                    }
                } catch (e: IOException) {
                    throw IllegalStateException(
                        "Unable to find a free port for running webpack", e
                    )
                }
            }
    }

    init {
        this.npmFolder = Objects.requireNonNull(npmFolder)
        port = runningPort
        reuseDevServer = config.reuseDevServer()

        // Check whether executor is provided by the caller (framework)
        val service = config.initParameters[Executor::class.java]
        val action: BiConsumer<Void?, in Throwable> = BiConsumer { value: Void?, exception: Throwable? ->
            // this will throw an exception if an exception has been thrown by
            // the waitFor task
            waitFor.getNow(null)
            runOnFutureComplete(config)
        }
        devServerStartFuture = if (service is Executor) {
            // if there is an executor use it to run the task
            waitFor.whenCompleteAsync(
                action,
                service as Executor?
            )
        } else {
            waitFor.whenCompleteAsync(action)
        }
    }
}
