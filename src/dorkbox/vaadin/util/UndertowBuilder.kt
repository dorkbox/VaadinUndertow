package dorkbox.vaadin.util

import io.undertow.Undertow
import io.undertow.Undertow.ListenerBuilder
import io.undertow.connector.ByteBufferPool
import io.undertow.server.HttpHandler
import org.xnio.Option
import org.xnio.XnioWorker
import java.util.concurrent.Executor
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

@Suppress("unused")
class UndertowBuilder() {
    private val builder = Undertow.builder()

    // the LAST http (or https) listener will recorded as the listener used.
    var httpListener: Triple<Boolean, String, Int> = Triple(false, "127.0.0.1", 8080) // defaults

    fun build(): Undertow {
        return builder.build()
    }

    fun addListener(listenerBuilder: ListenerBuilder): UndertowBuilder {
        //            listeners.add(new ListenerConfig(listenerBuilder));
        builder.addListener(listenerBuilder)
        return this
    }

    fun addHttpListener(port: Int, host: String): UndertowBuilder {
        httpListener = Triple(false, host, port)
        //            listeners.add(new ListenerConfig(ListenerType.HTTP, port, host, null, null, null));
        builder.addHttpListener(port, host)
        return this
    }

    fun addHttpsListener(
        port: Int,
        host: String,
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>
    ): UndertowBuilder {
        httpListener = Triple(true, host, port)
        //            listeners.add(new ListenerConfig(ListenerType.HTTPS, port, host, keyManagers, trustManagers, null));
        builder.addHttpsListener(port, host, keyManagers, trustManagers)
        return this
    }

    fun addHttpsListener(port: Int, host: String, sslContext: SSLContext): UndertowBuilder {
        httpListener = Triple(true, host, port)
        //            listeners.add(new ListenerConfig(ListenerType.HTTPS, port, host, sslContext, null));
        builder.addHttpsListener(port, host, sslContext)
        return this
    }

    fun addAjpListener(port: Int, host: String): UndertowBuilder {
        //            listeners.add(new ListenerConfig(ListenerType.AJP, port, host, null, null, null));
        builder.addAjpListener(port, host)
        return this
    }

    fun addHttpListener(port: Int, host: String, rootHandler: HttpHandler): UndertowBuilder {
        httpListener = Triple(false, host, port)
        //            listeners.add(new ListenerConfig(ListenerType.HTTP, port, host, null, null, rootHandler));
        builder.addHttpListener(port, host, rootHandler)
        return this
    }

    fun addHttpsListener(
        port: Int,
        host: String,
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        rootHandler: HttpHandler
    ): UndertowBuilder {
        httpListener = Triple(true, host, port)
        //            listeners.add(new ListenerConfig(ListenerType.HTTPS, port, host, keyManagers, trustManagers, rootHandler));
        builder.addHttpsListener(port, host, keyManagers, trustManagers, rootHandler)
        return this
    }

    fun addHttpsListener(port: Int, host: String, sslContext: SSLContext, rootHandler: HttpHandler): UndertowBuilder {
        httpListener = Triple(true, host, port)
        //            listeners.add(new ListenerConfig(ListenerType.HTTPS, port, host, sslContext, rootHandler));
        builder.addHttpsListener(port, host, sslContext, rootHandler)
        return this
    }

    fun addAjpListener(port: Int, host: String?, rootHandler: HttpHandler): UndertowBuilder {
        //            listeners.add(new ListenerConfig(ListenerType.AJP, port, host, null, null, rootHandler));
        builder.addAjpListener(port, host, rootHandler)
        return this
    }

    fun setBufferSize(bufferSize: Int): UndertowBuilder {
        builder.setBufferSize(bufferSize)
        return this
    }

    fun setIoThreads(ioThreads: Int): UndertowBuilder {
        builder.setIoThreads(ioThreads)
        return this
    }

    fun setWorkerThreads(workerThreads: Int): UndertowBuilder {
        builder.setWorkerThreads(workerThreads)
        return this
    }

    fun setDirectBuffers(directBuffers: Boolean): UndertowBuilder {
        builder.setDirectBuffers(directBuffers)
        return this
    }

    fun setHandler(handler: HttpHandler): UndertowBuilder {
        builder.setHandler(handler)
        return this
    }

    fun <T> setServerOption(option: Option<T>, value: T): UndertowBuilder {
        builder.setServerOption(option, value)
        return this
    }

    fun <T> setSocketOption(option: Option<T>, value: T): UndertowBuilder {
        builder.setSocketOption(option, value)
        return this
    }

    fun <T> setWorkerOption(option: Option<T>, value: T): UndertowBuilder {
        builder.setWorkerOption(option, value)
        return this
    }

    /**
     * When null (the default), a new [XnioWorker] will be created according
     * to the various worker-related configuration (ioThreads, workerThreads, workerOptions)
     * when [Undertow.start] is called.
     * Additionally, this newly created worker will be shutdown when [Undertow.stop] is called.
     * <br></br>
     *
     *
     * When non-null, the provided [XnioWorker] will be reused instead of creating a new [XnioWorker]
     * when [Undertow.start] is called.
     * Additionally, the provided [XnioWorker] will NOT be shutdown when [Undertow.stop] is called.
     * Essentially, the lifecycle of the provided worker must be maintained outside of the [Undertow] instance.
     */
    fun setWorker(worker: XnioWorker?): UndertowBuilder {
        builder.setWorker<Any>(worker)
        return this
    }

    fun setSslEngineDelegatedTaskExecutor(sslEngineDelegatedTaskExecutor: Executor): UndertowBuilder {
        builder.setSslEngineDelegatedTaskExecutor(sslEngineDelegatedTaskExecutor)
        return this
    }

    fun setByteBufferPool(byteBufferPool: ByteBufferPool): UndertowBuilder {
        builder.setByteBufferPool<Any>(byteBufferPool)
        return this
    }
}
