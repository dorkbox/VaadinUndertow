/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dorkbox.vaadin.undertow

import dorkbox.vaadin.util.logger
import io.undertow.UndertowLogger
import io.undertow.io.IoCallback
import io.undertow.predicate.Predicate
import io.undertow.predicate.Predicates
import io.undertow.server.HandlerWrapper
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.ResponseCodeHandler
import io.undertow.server.handlers.builder.HandlerBuilder
import io.undertow.server.handlers.cache.ResponseCache
import io.undertow.server.handlers.encoding.ContentEncodedResourceManager
import io.undertow.server.handlers.resource.*
import io.undertow.util.*
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.*

@Suppress("unused")
/**
 * MODIFIED from ResourceHandler to serve resources directly in the IO thread!
 * Some small tweaks to make it more friendly for kotlin (ZERO LOGIC CHANGES IN HANDLER!)
 *
 *
 * @param next  Handler that is called if no resource is found
 * @author Stuart Douglas
 */
class DirectResourceHandler(@Volatile private var resourceManager: ResourceManager?,
                            @Volatile private var resourceSupplier: ResourceSupplier = DefaultResourceSupplier(resourceManager),
                            val logger: Logger,
                            private val next: HttpHandler = ResponseCodeHandler.HANDLE_404) : HttpHandler {

    private val welcomeFiles = CopyOnWriteArrayList(arrayOf("index.html", "index.htm", "default.html", "default.htm"))
    /**
     * If directory listing is enabled.
     */
    @Volatile
    private var directoryListingEnabled = false

    /**
     * If the canonical version of paths should be passed into the resource manager.
     */
    /**
     * If this handler should use canonicalized paths.
     *
     * WARNING: If this is not true and [io.undertow.server.handlers.CanonicalPathHandler] is not installed in
     * the handler chain then is may be possible to perform a directory traversal attack. If you set this to false make
     * sure you have some kind of check in place to control the path.
     * @param canonicalizePaths If paths should be canonicalized
     */
    @Volatile
    var isCanonicalizePaths = true

    /**
     * The mime mappings that are used to determine the content type.
     */
    @Volatile
    private var mimeMappings = MimeMappings.DEFAULT
    @Volatile
    private var cachable = Predicates.truePredicate()
    @Volatile
    private var allowed = Predicates.truePredicate()

    /**
     * If this is set this will be the maximum time (in seconds) the client will cache the resource.
     *
     *
     * Note: Do not set this for private resources, as it will cause a Cache-Control: public
     * to be sent.
     *
     *
     * TODO: make this more flexible
     *
     *
     * This will only be used if the [.cachable] predicate returns true
     */
    @Volatile
    private var cacheTime: Int? = null

    @Volatile
    private var contentEncodedResourceManager: ContentEncodedResourceManager? = null



    @Throws(Exception::class)
    override fun handleRequest(exchange: HttpServerExchange) {
        if (exchange.requestMethod.equals(Methods.GET) || exchange.requestMethod.equals(Methods.POST)) {
            serveResource(exchange, true)
        }
        else if (exchange.requestMethod.equals(Methods.HEAD)) {
            serveResource(exchange, false)
        }
        else {
            if (KNOWN_METHODS.contains(exchange.requestMethod)) {
                exchange.statusCode = StatusCodes.METHOD_NOT_ALLOWED
                exchange.responseHeaders.add(Headers.ALLOW,
                                             arrayOf(Methods.GET_STRING, Methods.HEAD_STRING, Methods.POST_STRING).joinToString(", "))
            }
            else {
                exchange.statusCode = StatusCodes.NOT_IMPLEMENTED
            }
            exchange.endExchange()
        }
    }

    @Throws(Exception::class)
    private fun serveResource(exchange: HttpServerExchange,
                              sendContent: Boolean) {

        if (DirectoryUtils.sendRequestedBlobs(exchange)) {
            return
        }

        if (!allowed.resolve(exchange)) {
            exchange.statusCode = StatusCodes.FORBIDDEN
            exchange.endExchange()
            return
        }

        val cache = exchange.getAttachment(ResponseCache.ATTACHMENT_KEY)
        val cachable = this.cachable.resolve(exchange)

        //we set caching headers before we try and serve from the cache
        if (cachable && cacheTime != null) {
            exchange.responseHeaders.put(Headers.CACHE_CONTROL, "public, max-age=" + cacheTime!!)
            val date = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cacheTime!!.toLong())
            val dateHeader = DateUtils.toDateString(Date(date))
            exchange.responseHeaders.put(Headers.EXPIRES, dateHeader)
        }

        if (cache != null && cachable) {
            if (cache.tryServeResponse()) {
                return
            }
        }

        var resource: Resource? = null
        try {
            if (File.separatorChar == '/' || !exchange.relativePath.contains(File.separator)) {
                //we don't process resources that contain the seperator character if this is not /
                //this prevents attacks where people use windows path seperators in file URLS's
                resource = resourceSupplier.getResource(exchange, canonicalize(exchange.relativePath))
            }
        } catch (e: IOException) {
            clearCacheHeaders(exchange)
            UndertowLogger.REQUEST_IO_LOGGER.ioException(e)
            exchange.statusCode = StatusCodes.INTERNAL_SERVER_ERROR
            exchange.endExchange()
            return
        }

        if (resource == null) {
            clearCacheHeaders(exchange)
            //usually a 404 handler
            next.handleRequest(exchange)
            return
        }

        if (resource.isDirectory) {
            val indexResource: Resource?
            try {
                indexResource = getIndexFiles(exchange, resourceSupplier, resource.path, welcomeFiles)
            } catch (e: IOException) {
                UndertowLogger.REQUEST_IO_LOGGER.ioException(e)
                exchange.statusCode = StatusCodes.INTERNAL_SERVER_ERROR
                exchange.endExchange()
                return
            }

            if (indexResource == null) {
                if (directoryListingEnabled) {
                    DirectoryUtils.renderDirectoryListing(exchange, resource)
                    return
                }
                else {
                    exchange.statusCode = StatusCodes.FORBIDDEN
                    exchange.endExchange()
                    return
                }
            }
            else if (!exchange.requestPath.endsWith("/")) {
                exchange.statusCode = StatusCodes.FOUND
                exchange.responseHeaders.put(Headers.LOCATION, RedirectBuilder.redirect(exchange, exchange.relativePath + "/", true))
                exchange.endExchange()
                return
            }
            resource = indexResource
        }
        else if (exchange.relativePath.endsWith("/")) {
            //UNDERTOW-432
            exchange.statusCode = StatusCodes.NOT_FOUND
            exchange.endExchange()
            return
        }

        val etag = resource.eTag
        val lastModified = resource.lastModified
        if (!ETagUtils.handleIfMatch(exchange, etag, false) || !DateUtils.handleIfUnmodifiedSince(exchange, lastModified)) {
            exchange.statusCode = StatusCodes.PRECONDITION_FAILED
            exchange.endExchange()
            return
        }
        if (!ETagUtils.handleIfNoneMatch(exchange, etag, true) || !DateUtils.handleIfModifiedSince(exchange, lastModified)) {
            exchange.statusCode = StatusCodes.NOT_MODIFIED
            exchange.endExchange()
            return
        }
        val contentEncodedResourceManager = this@DirectResourceHandler.contentEncodedResourceManager
        val contentLength = resource.contentLength

        if (contentLength != null && !exchange.responseHeaders.contains(Headers.TRANSFER_ENCODING)) {
            exchange.responseContentLength = contentLength
        }
        var rangeResponse: ByteRange.RangeResponseResult? = null
        var start: Long = -1
        var end: Long = -1
        if (resource is RangeAwareResource && resource.isRangeSupported && contentLength != null && contentEncodedResourceManager == null) {

            exchange.responseHeaders.put(Headers.ACCEPT_RANGES, "bytes")
            //TODO: figure out what to do with the content encoded resource manager
            val range = ByteRange.parse(exchange.requestHeaders.getFirst(Headers.RANGE))
            if (range != null && range.ranges == 1 && resource.contentLength != null) {
                rangeResponse = range.getResponseResult(resource.contentLength!!,
                                                        exchange.requestHeaders.getFirst(Headers.IF_RANGE),
                                                        resource.lastModified,
                                                        if (resource.eTag == null) null else resource.eTag.tag)
                if (rangeResponse != null) {
                    start = rangeResponse.start
                    end = rangeResponse.end
                    exchange.statusCode = rangeResponse.statusCode
                    exchange.responseHeaders.put(Headers.CONTENT_RANGE, rangeResponse.contentRange)
                    val length = rangeResponse.contentLength
                    exchange.responseContentLength = length
                    if (rangeResponse.statusCode == StatusCodes.REQUEST_RANGE_NOT_SATISFIABLE) {
                        return
                    }
                }
            }
        }
        //we are going to proceed. Set the appropriate headers

        if (!exchange.responseHeaders.contains(Headers.CONTENT_TYPE)) {
            val contentType = resource.getContentType(mimeMappings)
            if (contentType != null) {
                exchange.responseHeaders.put(Headers.CONTENT_TYPE, contentType)
            }
            else {
                exchange.responseHeaders.put(Headers.CONTENT_TYPE, "application/octet-stream")
            }
        }
        if (lastModified != null) {
            exchange.responseHeaders.put(Headers.LAST_MODIFIED, resource.lastModifiedString)
        }
        if (etag != null) {
            exchange.responseHeaders.put(Headers.ETAG, etag.toString())
        }

        if (contentEncodedResourceManager != null) {
            try {
                val encoded = contentEncodedResourceManager.getResource(resource, exchange)
                if (encoded != null) {
                    exchange.responseHeaders.put(Headers.CONTENT_ENCODING, encoded.contentEncoding)
                    exchange.responseHeaders.put(Headers.CONTENT_LENGTH, encoded.resource.contentLength!!)
                    encoded.resource.serve(exchange.responseSender, exchange, IoCallback.END_EXCHANGE)
                    return
                }

            } catch (e: IOException) {
                //TODO: should this be fatal
                UndertowLogger.REQUEST_IO_LOGGER.ioException(e)
                exchange.statusCode = StatusCodes.INTERNAL_SERVER_ERROR
                exchange.endExchange()
                return
            }
        }

        if (!sendContent) {
            exchange.endExchange()
        }
        else if (rangeResponse != null) {
            (resource as RangeAwareResource).serveRange(exchange.responseSender, exchange, start, end, IoCallback.END_EXCHANGE)
        }
        else {
            resource.serve(exchange.responseSender, exchange, IoCallback.END_EXCHANGE)
        }
    }

    private fun clearCacheHeaders(exchange: HttpServerExchange) {
        exchange.responseHeaders.remove(Headers.CACHE_CONTROL)
        exchange.responseHeaders.remove(Headers.EXPIRES)
    }

    @Throws(IOException::class)
    private fun getIndexFiles(exchange: HttpServerExchange,
                              resourceManager: ResourceSupplier?,
                              base: String,
                              possible: List<String>): Resource? {
        val realBase: String
        if (base.endsWith("/")) {
            realBase = base
        }
        else {
            realBase = "$base/"
        }
        for (possibility in possible) {
            val index = resourceManager!!.getResource(exchange, canonicalize(realBase + possibility))
            if (index != null) {
                return index
            }
        }
        return null
    }

    private fun canonicalize(s: String): String {
        return if (isCanonicalizePaths) {
            CanonicalPathUtils.canonicalize(s)
        }
        else s
    }

    fun isDirectoryListingEnabled(): Boolean {
        return directoryListingEnabled
    }

    fun setDirectoryListingEnabled(directoryListingEnabled: Boolean): DirectResourceHandler {
        this.directoryListingEnabled = directoryListingEnabled
        return this
    }

    fun addWelcomeFiles(vararg files: String): DirectResourceHandler {
        this.welcomeFiles.addAll(Arrays.asList(*files))
        return this
    }

    fun setWelcomeFiles(vararg files: String): DirectResourceHandler {
        this.welcomeFiles.clear()
        this.welcomeFiles.addAll(Arrays.asList(*files))
        return this
    }

    fun getMimeMappings(): MimeMappings {
        return mimeMappings
    }

    fun setMimeMappings(mimeMappings: MimeMappings): DirectResourceHandler {
        this.mimeMappings = mimeMappings
        return this
    }

    fun getCachable(): Predicate {
        return cachable
    }

    fun setCachable(cachable: Predicate): DirectResourceHandler {
        this.cachable = cachable
        return this
    }

    fun getAllowed(): Predicate {
        return allowed
    }

    fun setAllowed(allowed: Predicate): DirectResourceHandler {
        this.allowed = allowed
        return this
    }

    fun getResourceSupplier(): ResourceSupplier? {
        return resourceSupplier
    }

    fun setResourceSupplier(resourceSupplier: ResourceSupplier): DirectResourceHandler {
        this.resourceSupplier = resourceSupplier
        this.resourceManager = null
        return this
    }

    fun getResourceManager(): ResourceManager? {
        return resourceManager
    }

    fun setResourceManager(resourceManager: ResourceManager): DirectResourceHandler {
        this.resourceManager = resourceManager
        this.resourceSupplier = DefaultResourceSupplier(resourceManager)
        return this
    }

    fun getCacheTime(): Int? {
        return cacheTime
    }

    fun setCacheTime(cacheTime: Int): DirectResourceHandler {
        this.cacheTime = cacheTime
        return this
    }

    fun getContentEncodedResourceManager(): ContentEncodedResourceManager? {
        return contentEncodedResourceManager
    }

    fun setContentEncodedResourceManager(contentEncodedResourceManager: ContentEncodedResourceManager): DirectResourceHandler {
        this.contentEncodedResourceManager = contentEncodedResourceManager
        return this
    }

    class Builder : HandlerBuilder {

        override fun name(): String {
            return "resource"
        }

        override fun parameters(): Map<String, Class<*>> {
            val params = HashMap<String, Class<*>>()
            params["location"] = String::class.java
            params["allow-listing"] = Boolean::class.java
            return params
        }

        override fun requiredParameters(): Set<String> {
            return setOf("location")
        }

        override fun defaultParameter(): String {
            return "location"
        }

        override fun build(config: Map<String, Any>): HandlerWrapper {
            return Wrapper(config["location"] as String, config["allow-listing"] as Boolean)
        }

    }

    private class Wrapper(private val location: String,
                          private val allowDirectoryListing: Boolean) : HandlerWrapper {

        override fun wrap(handler: HttpHandler): HttpHandler {
            val resourceManager = PathResourceManager(Paths.get(location), 1024)
            // use a default logger
            val resourceHandler = DirectResourceHandler(resourceManager, DefaultResourceSupplier(resourceManager), logger())
            resourceHandler.setDirectoryListingEnabled(allowDirectoryListing)
            return resourceHandler
        }
    }

    companion object {

        /**
         * Set of methods prescribed by HTTP 1.1. If request method is not one of those, handler will
         * return NOT_IMPLEMENTED.
         */
        private val KNOWN_METHODS = HashSet<HttpString>()

        init {
            KNOWN_METHODS.add(Methods.OPTIONS)
            KNOWN_METHODS.add(Methods.GET)
            KNOWN_METHODS.add(Methods.HEAD)
            KNOWN_METHODS.add(Methods.POST)
            KNOWN_METHODS.add(Methods.PUT)
            KNOWN_METHODS.add(Methods.DELETE)
            KNOWN_METHODS.add(Methods.TRACE)
            KNOWN_METHODS.add(Methods.CONNECT)
        }
    }
}
