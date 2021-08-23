package dorkbox.vaadin.util

import ch.qos.logback.classic.Level
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.ResponseCodeHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference


open class WebServer(private val threadGroup: ThreadGroup, protected val logger: Logger) {
    protected val serverBuilder = Undertow.builder()

            .setSocketOption(org.xnio.Options.REUSE_ADDRESSES, true)
            .setSocketOption(org.xnio.Options.SSL_ENABLED, true)

            .setServerOption(UndertowOptions.MAX_BUFFERED_REQUEST_SIZE, 163840) // 16384 is default
            .setServerOption(UndertowOptions.SSL_USER_CIPHER_SUITES_ORDER, true)
            .setServerOption(UndertowOptions.ENABLE_STATISTICS, true)!!


    private var webServer: Undertow? = null
    private val extraStartables = mutableListOf<Runnable>()
    private val extraStoppables = mutableListOf<Runnable>()

    fun addStartable(startable: () -> Unit) {
        extraStartables.add( Runnable { startable() })
    }
    fun addStoppable(stoppable: () -> Unit) {
        extraStoppables.add( Runnable { stoppable() })
    }

    fun setMainHandlerForSingleServerStartup(httpHandler: HttpHandler) {
//        if (WebServerConfig.httpsEnabled) {
//            // so we will always upgrade from HTTP -> HTTPS
//            serverBuilder.addHttpListener(PortInformation.http, NetworkUtil.EXTERNAL_IPV4, createHttpsRedirect())
//
//            // make this always be HTTPS
//            serverBuilder.addHttpsListener(PortInformation.https, NetworkUtil.EXTERNAL_IPV4, WebServerUtils.createSSLContext
//            ("keystore", "dorkbox"), WebServerUtils.createHttps(httpHandler))
//        }
//        else {
//            // make sure we serve the HTTP page
//            serverBuilder.addHttpListener(PortInformation.http, NetworkUtil.EXTERNAL_IPV4, httpHandler)
//        }

        // ALWAYS have a 404 handler prepared!
        serverBuilder.setHandler(ResponseCodeHandler.HANDLE_404)
    }

    fun startServer(logger: Logger) {
        // always show this part.
        val webLogger = logger as ch.qos.logback.classic.Logger

        // save the logger level, so that on startup we can see more detailed info, if necessary.
        val level = webLogger.level
        if (logger.isTraceEnabled) {
            webLogger.level = Level.TRACE
        }
        else {
            webLogger.level = Level.INFO
        }

        val server = serverBuilder.build()
        try {
            // NOTE: we start this in a NEW THREAD so we can create and use a thread-group for all of the undertow threads created. This allows
            //  us to keep our main thread group "un-cluttered" when analyzing thread/stack traces.
            //
            //  This is a hacky, but undertow does not support setting the thread group in the builder.

            val exceptionThrown = AtomicReference<Exception>()
            val latch = CountDownLatch(1)

            Thread(threadGroup) {
                try {
                    server.start()
                    webServer = server

//                    WebServerConfig.logStartup(logger)

                    extraStartables.forEach { it ->
                        it.run()
                    }
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
        finally {
            webLogger.level = level
        }
    }


    fun stopServer(logger: Logger) {
        // always show this part.
        val webLogger = logger as ch.qos.logback.classic.Logger
        val undertowLogger = LoggerFactory.getLogger("org.xnio.nio") as ch.qos.logback.classic.Logger

        // save the logger level, so that on shutdown we can see more detailed info, if necessary.
        val level = webLogger.level
        val undertowLevel = undertowLogger.level
        if (logger.isTraceEnabled) {
            webLogger.level = Level.TRACE
            undertowLogger.level = Level.TRACE
        }
        else {
            // we REALLY don't care about shutdown errors. we are shutting down!! (atmosphere likes to screw with us!)
            webLogger.level = Level.OFF
            undertowLogger.level = Level.OFF
        }

        try {
            webServer?.worker?.shutdown()
            webServer?.stop()

            extraStoppables.forEach { it ->
                it.run()
            }
        }
        finally {
            webLogger.level = level
            undertowLogger.level = undertowLevel
        }
    }
}

