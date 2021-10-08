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

//import dorkbox.network.Client
//import dorkbox.network.Configuration
//import dorkbox.network.Server
//import dorkbox.network.connection.Connection
//import dorkbox.network.connection.EndPoint
//import dorkbox.network.connection.Listener
//import dorkbox.network.connection.connectionType.ConnectionRule
//import dorkbox.network.connection.connectionType.ConnectionType
//import dorkbox.network.rmi.RemoteObjectCallback
//import dorkbox.network.serialization.Serialization
//import io.netty.util.NetUtil
//import java.io.IOException
//import java.net.Socket
//import java.time.LocalDateTime
//import java.util.*
//import java.util.concurrent.atomic.AtomicBoolean
//
///**
// * Provides
// * - command monitor : that will sit and wait for CLI commands to be executed (after the app starts up)
// * - remote stop : allows another instance of this app to stop the current instance
// *
// * @param host null to listen to the ANY interface
// * @param service what type of service the SERVER is to run under
//*/
//abstract class CommandLineApp(private val logger: org.slf4j.Logger, private val host: String?, service: Servers) : ShutdownService {
//    private val shutdownHook = Thread(Runnable { this.shutdownThread() })
//
//    internal var shutdownInProgress = AtomicBoolean()   // Middle of shutting down
//    internal var isShutdown = AtomicBoolean()           // Completed shutting down
//
//    private val shutdownLock = Object()
//
//    /**
//     * A list of actions that must be run on shutdown
//     */
//    protected val shutdownActions: MutableList<Runnable> = Collections.synchronizedList(LinkedList())
//
//    private val actionMap = LinkedHashMap<String, CommandAction>()
//
//    private val tcpPort = service.port
//    private val appName = service.name
//
//    protected val server: Server<Connection>
//
//    val isRunning: Boolean
//        get() = server.isRunning
//
//
//    init {
//        // Register handler to shutdown server if system is shutdown
//        Runtime.getRuntime()
//                .addShutdownHook(shutdownHook)
//
//        // Remote method invocation for controlling netref via enterprise
//        val configuration = Configuration()
//        configuration.tcpPort = tcpPort
//        configuration.host = host
//
//        configuration.serialization = Serialization.DEFAULT()
//        configuration.localChannelName = EndPoint.LOCAL_CHANNEL // we also enable in-JVM local channels
//
//        /*
//         * Based on which CLIENT is connecting to this SERVER, it MUST have an identical serialization configuration! If not, then there will be
//         * serialization errors for RMI or whatever else.
//         */
//
//        // implementations in NR Server/Engine (this is always first)
//        configuration.serialization.registerRmi(ShutdownService::class.java, CommandLineApp::class.java)
//
//        getConfiguration(configuration, Server::class.java)
//
//        val serviceId: Int
//        try {
//            server = Server(configuration)
//
//            // we disable remote key validation BECAUSE we can accept connections on localhost AND we have multiple connections on it VIA SSH tunneling.
//            server.disableRemoteKeyValidation()
//
//            val disabledEncryptionIps = Args.server.disabledEncryptionIps
//            for (disabledEncryptionIp in disabledEncryptionIps) {
//                if (disabledEncryptionIp == "127.0.0.1/32") {
//                    // actually use what is detected as the localhost... simply because it's possible that this can be different
//                    server.addConnectionTypeFilter(ConnectionRule(NetUtil.LOCALHOST, 32, ConnectionType.COMPRESS))
//                }
//                else {
//                    val ip = disabledEncryptionIp.substringBefore("/")
//                    val cidr = disabledEncryptionIp.substringAfter("/").toInt()
//                    server.addConnectionTypeFilter(ConnectionRule(ip, cidr, ConnectionType.COMPRESS))
//                }
//            }
//
//            serviceId = server.createGlobalObject(this)
//        }
//        catch (e: Exception) {
//            throw RuntimeException("Unable to start up server.", e)
//        }
//
//        ServerService.SERVER_SHUTDOWN.validate(serviceId)
//    }
//
//    /**
//     * Based on which CLIENT is connecting to this SERVER, it MUST have an identical serialization configuration!
//     *
//     * If not, then there will be serialization errors for RMI or whatever else.
//     *
//     * @param endpointClass should be either Server.class or Client.class
//     */
//    protected open fun getConfiguration(configuration: Configuration, endpointClass: Class<out EndPoint>) {}
//
//
//
//    fun start() {
//        // Send email when server starts,
//        SmtpProcessor.sendNetRefTeamEmail("$appName Started", "$appName started at IP ???? a customers location.")
//
//        commandMonitor()
//
//        // don't block because we do that ourselves
//        server.bind(false)
//
//        postBindActions()
//    }
//
//
//
//    fun block() {
//        if (!Args.isUnitTest) {
//            // we don't want to block during unit tests!
//
//            synchronized(shutdownLock) {
//                try {
//                    shutdownLock.wait()
//                }
//                catch (e: Exception) {
//                    logger.error("Error waiting for shutdown lock", e)
//                }
//
//            }
//        }
//
//        logger.info("'{}' shutdown finished.", appName)
//    }
//
//    /**
//     * actions to occur AFTER the server has bound to the socket
//     */
//    protected open fun postBindActions() {}
//
//
//    protected fun blockNotify() {
//        synchronized(shutdownLock) {
//            shutdownLock.notifyAll()
//        }
//    }
//
//    /**
//     * Only adds a command action if we are NOT a daemon process
//     */
//    protected fun addCommandAction(vararg actions: CommandAction) {
//        if (!Args.isDaemon) {
//            synchronized(actionMap) {
//                for (action in actions) {
//                    actionMap[action.command
//                            .toLowerCase(Locale.US)] = action
//                }
//            }
//        }
//    }
//
//    /**
//     * Only adds a command action if we are NOT a daemon process
//     */
//    protected fun addCommandAction(action: CommandAction) {
//        if (!Args.isDaemon) {
//            synchronized(actionMap) {
//                actionMap.put(action.command
//                                      .toLowerCase(Locale.US), action
//                             )
//            }
//        }
//    }
//
//    private fun commandMonitor(threadGroup: ThreadGroup = Thread.currentThread().threadGroup) {
//        // if we start in "daemon mode" in a bash shell, we won't have access to a scanner.
//        if (Args.isDaemon) {
//            return
//        }
//
//        // start an "exit" monitor, so that if the user types exit in the console (in order to properly shutdown the database)
//        // we can watch for it.
//        val thread = Thread(threadGroup, {
//            // give some time for the logs (if available) to settle
//            try {
//                Thread.sleep(2000L)
//            }
//            catch (ignored: InterruptedException) {
//            }
//
//            printInfo()
//
//            try {
//                System.err.println("Please type \"exit\" to safely stop the app")
//                Scanner(System.`in`).use { scanner ->
//                    // read in a line at a time
//                    scanner.useDelimiter(OS.LINE_SEPARATOR)
//
//                    var next = scanner.next()
//                    while (next != null) {
//                        next = next.toLowerCase(Locale.US)
//                        if (next == "exit") {
//                            break
//                        }
//
//                        // if there is a space, this means that there are parameters.
//                        var parameters: String? = null
//                        val i = next.indexOf(' ')
//                        if (i > 0) {
//                            parameters = next.substring(i + 1)
//                            next = next.substring(0, i)
//                        }
//
//                        if (parameters == null) {
//                            parameters = ""
//                        }
//
//                        try {
//                            synchronized(actionMap) {
//                                val commandAction = actionMap[next]
//                                if (commandAction != null) {
//                                    commandAction.action(parameters)
//                                    printInfo()
//                                }
//                            }
//                        }
//                        catch (e: Exception) {
//                            logger.error("Error executing command '$next'.", e)
//                        }
//
//                        next = scanner.next()
//                    }
//                }
//
//                shutdown()
//            }
//            catch (ignored: Exception) {
//                // if we start in "daemon mode" in a bash shell, scanner.next() will crash.
//            }
//        }, "Application Exit Monitor")
//        thread.isDaemon = true
//        thread.start()
//    }
//
//    private fun printInfo() {
//        synchronized(actionMap) {
//            val actions = StringBuilder(256)
//            actions.append("System is listening for the following commands: ")
//                    .append(OS.LINE_SEPARATOR)
//            actions.append('\t')
//                    .append("exit")
//                    .append(OS.LINE_SEPARATOR)
//
//            for ((key, value) in actionMap) {
//                val comment = value
//                        .comment
//
//                actions.append('\t')
//                        .append(key)
//
//                var length = key.length
//                while (length++ < 10) {
//                    actions.append(' ')
//                }
//
//                if (comment != null && comment.isNotEmpty()) {
//                    actions.append('\t')
//                            .append("(")
//                            .append(comment)
//                            .append(")")
//                }
//
//                actions.append(OS.LINE_SEPARATOR)
//            }
//
//            System.err.println(actions)
//        }
//    }
//
//    private fun shutdownThread() {
//        // only remove our shutdown hook if we are not in the ABORT shutdown process (which is what the shutdown hook does)
//        val thread = Thread.currentThread()
//        if (thread !== shutdownHook) {
//            try {
//                logger.debug("Removing Shutdown Hook")
//                Runtime.getRuntime()
//                        .removeShutdownHook(shutdownHook)
//            }
//            catch (ignored: Exception) {
//            }
//
//        }
//
//        shutdown()
//    }
//
//
//    /**
//     * Creates a client to send a remote shutdown command. Stays in this method until the remote app is fully shutdown
//     */
//    fun sendShutdown() {
//        var hostForShutdown = host
//        if (host == null || host == NetworkUtil.EXTERNAL_IPV4) {
//            // this means we are listening on the any interface, but for shutdown, we have to connect to localhost...
//            hostForShutdown = NetworkUtil.LOCALHOST
//        }
//
//        // since we check the socket, if we are NOT connected to a socket, then we're done.
//        try {
//            Socket(hostForShutdown, tcpPort).use { sock ->
//                if (!sock.isConnected) {
//                    // if we can NOT connect to the socket, it means that we are not running, so exit early.
//
//                    logger.info("No existing instance to close...")
//                    logger.info("Server has shut down.")
//                    return
//                }
//            }
//        }
//        catch (ignored: Exception) {
//        }
//
//        // NOTE: this causes problems in the server!!
//        // // since we check the socket, if we are NOT connected to a socket, then we're done.
//        // try (Socket sock = new Socket(serverHost, tcpPort)) {
//        //     if (!sock.isConnected()) {
//        //         // if we can NOT connect to the socket, it means that we are not running, so exit early.
//        //
//        //         logger.info("No existing instance to close...");
//        //         logger.info("Server has shut down.");
//        //         return;
//        //     }
//        // } catch (Exception ignored) {
//        // }
//
//
//
//        val configuration = Configuration()
//        configuration.tcpPort = tcpPort
//        configuration.host = hostForShutdown
//
//
//        // this MUST match what the server has!
//        configuration.serialization = Serialization.DEFAULT()
//
//        /*
//         * Based on which CLIENT is connecting to this SERVER, it MUST have an identical serialization configuration! If not, then there will be
//         * serialization errors for RMI or whatever else.
//         */
//
//        getConfiguration(configuration, Client::class.java)
//
//        val shutdown = Object()
//        val didShutdown = AtomicBoolean()
//
//
//        var client: Client<Connection>? = null
//        try {
//            client = Client(configuration)
//
//            // we disable remote key validation BECAUSE we can accept connections on localhost AND we have multiple connections on it VIA SSH tunneling.
//            client.disableRemoteKeyValidation()
//
//            val callback = RemoteObjectCallback<ShutdownService> { remoteObject ->
//                // true if we are shutdown down, false if we are in progress. Must be on a new thread
//                if (remoteObject == null) {
//                    didShutdown.set(true)
//
//                    synchronized(shutdown) {
//                        shutdown.notifyAll()
//                    }
//                }
//                else {
//                    try {
//                        remoteObject.shutdown()
//                    }
//                    catch (e: Exception) {
//                        logger.error("Error during remote shutdown command!", e)
//                    }
//
//                }
//            }
//
//            client.listeners()
//                    .add(Listener.OnConnected<Connection> { connection ->
//                        connection.getRemoteObject(ServerService.SERVER_SHUTDOWN.rmiId, callback)
//                    })
//        }
//        catch (e: Exception) {
//            e.printStackTrace()
//        }
//
//        // always try to connect to the remote netref. Retry max 10 times
//        var maxCount = 10
//        while (client != null && !didShutdown.get() && maxCount-- > 0) {
//            try {
//                client.connect(5000)
//
//                if (!client.isConnected) {
//                    client.stop()
//                    // this means we got disconnected right away
//                    break
//                }
//
//                logger.info("Waiting for remote application to shut down...")
//                synchronized(shutdown) {
//                    shutdown.wait(5000)
//                }
//
//                if (didShutdown.get()) {
//                    break
//                }
//
//                client.close()
//            }
//            catch (e: Exception) {
//                client.stop()
//                // this means that we could not connect, thus the remote app has shutdown
//                break
//            }
//
//        }
//
//        if (maxCount > 0) {
//            logger.info("Successfully stopped remote application.")
//        }
//        else {
//            logger.error("Unable to shutdown the server in a reasonable amount of time.")
//        }
//    }
//
//
//    /**
//     * Request to shutdown.
//     */
//    override fun shutdown() {
//        if (isShutdown.get() || shutdownInProgress.get()) {
//            // is shut down or in progress of shutting down
//            return
//        }
//
//        // this must be run on a NEW thread, so it doesn't block anything
//        val thread = Thread {
//            if (shutdownInProgress.getAndSet(true)) {
//                // in progress of shutting down!
//                return@Thread
//            }
//
//            // we wait for a little bit here for the network connections to cleanup...
//            logger.debug("Waiting for shutdown cleanup...")
//            try {
//                Thread.sleep(2_000L)
//            }
//            catch (ignored: InterruptedException) {
//            }
//
//            server.stop()
//            server.waitForShutdown()
//
//
//            // rarely, different threads will call shutdown (which causes 2+ shutdown events to occur. We make sure that only ONE thread can
//            // shutdown at a time.
//            val cleanedShutdown: MutableList<Runnable>
//            logger.debug("Clearing Shutdown Actions")
//            synchronized(shutdownActions) {
//                // we shutdown items in the reverse order they were added
//                shutdownActions.reverse()
//
//                cleanedShutdown = shutdownActions.toMutableList()
//                shutdownActions.clear()
//            }
//
//            logger.info("Shutting down the server.")
//
//            if (!Args.stopIssued()) {
//                // Send email when app server stops, but NOT if it's the issuing "stop" server (which also calls stop)
//                logger.info("Sending stopped Email for '{}' application", appName)
//                SmtpProcessor.sendNetRefTeamEmail("$appName Stopped", "$appName stopped at a customers location.")
//            }
//
//            // it's possible to call stop more than once
//            if (cleanedShutdown.isNotEmpty()) {
//                val iterator = cleanedShutdown.iterator()
//                while (iterator.hasNext()) {
//                    val shutdownAction = iterator.next()
//                    iterator.remove()
//                    try {
//                        shutdownAction.run()
//                    }
//                    catch (e: Exception) {
//                        val isIO = IOException::class.java.isAssignableFrom(e.javaClass)
//                        if (!isIO) {
//                            logger.error("Error during shutdown.", e)
//                        }
//                    }
//
//                }
//            }
//
//            isShutdown.set(true)
//
//            // make sure our main thread exits, only AFTER everything else has shutdown
//            blockNotify()
//
//            logger.info("$appName app has shut down.")
//        }
//        thread.name = "Remote shutdown thread"
//        thread.isDaemon = true
//        thread.start()
//    }
//
//    fun showStartupStats() {
//        val jvmName = System.getProperty("java.vm.name")
//        val jvmVersion = System.getProperty("java.version")
//        val jvmVendor = System.getProperty("java.vm.specification.vendor")
//        logger.info("Execution JVM: $jvmVendor  $jvmName $jvmVersion")
//        logger.info("Execution arguments: " + Args.commandlineArguments)
//
//        logger.info("Starting {} : {} ", appName, LocalDateTime.now().toString())
//    }
//
//    abstract fun run()
//}
