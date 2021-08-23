package dorkbox.vaadin.util

import io.undertow.Handlers
import io.undertow.attribute.ExchangeAttributes
import io.undertow.predicate.Predicates
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.LearningPushHandler
import io.undertow.server.session.InMemorySessionManager
import io.undertow.server.session.SessionAttachmentHandler
import io.undertow.server.session.SessionCookieConfig
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.util.concurrent.Executor
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Starts blocking and runs in a new thread, as necessary
 */
fun HttpServerExchange.runBlocking(executor: Executor? = null, handler: HttpHandler) {
    if (!isBlocking) {
        startBlocking()

        if (isInIoThread) {
            dispatch(executor, handler)  // run a new one
        } else {
            handler.handleRequest(this) // process normal
        }
    }
    else {
        handler.handleRequest(this) // process normal
    }
}

/**
 * Starts blocking and runs in a new thread, as necessary
 */
fun HttpServerExchange.runBlocking(handler: HttpHandler) {
    runBlocking(null, handler)
}

object WebServerUtils {
    private const val NETREF_START_DATE = 1374710400L // July 25, 2013 (when we named netref)

    fun setNoCache(exchange: HttpServerExchange) {
        // NOTE: IE 9 requires the following HTML headers to prevent caching!
        //     <meta HTTP-EQUIV="Pragma" CONTENT="no-cache">
        //     <meta HTTP-EQUIV="Expires" CONTENT="-1">
        // The actual HTML that we serve has to be edited to include this.
        // SEE: https://superuser.com/questions/461285/how-to-disable-caching-in-internet-explorer-9

        val headers = exchange.responseHeaders
        headers.put(Headers.CACHE_CONTROL, "no-cache, no-store, private, must-revalidate, max-age=0, max-stale=0, post-check=0, pre-check=0")
        headers.put(Headers.PRAGMA, "no-cache")
        headers.put(Headers.CONNECTION, "Close")
        headers.put(Headers.EXPIRES, NETREF_START_DATE) // July 25, 2013 (when we named netref)
    }

    @Throws(Exception::class)
    fun createSSLContext(keyStore: String, storePassword: String): SSLContext {
        val javaKeyStore = loadKeyStore(keyStore, storePassword)

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(javaKeyStore, storePassword.toCharArray())
        val keyManagers = keyManagerFactory.keyManagers

        val trustAllCerts = arrayOf<X509TrustManager>(DummyTrustManager())

        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustAllCerts, null)

        // by default, the SSL context here has the unsafe SSL ciphers ALREADY removed
        return sslContext
    }

    private fun loadKeyStore(keyStore: String, storePassword: String): KeyStore {
        val stream = Files.newInputStream(Paths.get(keyStore)) ?: throw IllegalArgumentException("Could not load keystore '$keyStore'")
        stream.use { inputStream ->
            val loadedKeystore = KeyStore.getInstance("JKS")
            loadedKeystore.load(inputStream, storePassword.toCharArray())
            return loadedKeystore
        }
    }



    /**
     * Creates an HTTPs temporary redirect handler that will send all requests to HTTPS first
     */
    fun createHttpsRedirect(): HttpHandler {
        return HttpHandler { exchange ->
            exchange.responseHeaders.add(Headers.LOCATION, "https://" + exchange.hostName + exchange.relativePath)
            exchange.statusCode = StatusCodes.TEMPORARY_REDIRECT
        }
    }

    /**
     * Creates an HTTP/2 handler that will GUARANTEE all requests are upgraded to HTTPS first
     */
    fun createHttps(nextHandler: HttpHandler): HttpHandler {
        return Handlers.predicate(Predicates.secure(), nextHandler, createHttpsRedirect())
    }

    /**
     * Creates an HTTP/2 handler that will just track and serve HTTP requests
     */
    fun createHttp2(nextHandler: HttpHandler): HttpHandler {
        val transportTrackingHandler = Handlers.header(nextHandler, "x-undertow-transport", ExchangeAttributes.transportProtocol())

        val learningPushHandler = LearningPushHandler(100, -1, transportTrackingHandler)
        return SessionAttachmentHandler(learningPushHandler, InMemorySessionManager("learnedSessions"), SessionCookieConfig())
    }

    /**
     * Gets the remote IP address either from the NGINX/AWS header, or via the physical remote address.
     *
     * The physical remote address is worthless if we are using NGINX/AWS to proxy the web request, as it will be the
     * NGINX/AWS server IP instead of the actual "real" ip.
     */
    fun getRemoteAddress(exchange: HttpServerExchange): String {
        // X-Forwarded-For standard (also is set by Nginx and AWS). If we do something different, this will change.
        return exchange.requestHeaders["X-Forwarded-For"]?.peekFirst() ?: exchange.sourceAddress.address.hostAddress
    }
}
