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

import com.vaadin.flow.server.VaadinRequest
import com.vaadin.flow.server.VaadinService
import com.vaadin.flow.server.VaadinSession
import com.vaadin.flow.shared.ApplicationConstants
import java.io.Serializable
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.BiConsumer
import java.util.regex.Pattern

/**
 * THIS IS COPIED DIRECTLY FROM VAADIN 14.7.1 (flow 2.7.1)
 *
 * CHANGES FROM DEFAULT ARE MANAGED AS DIFFERENT REVISIONS.
 *
 * The initial commit is exactly as-is from vaadin.
 *
 * This file is NOT extensible/configurable AT-ALL, so this is required...
 *
 * Contains helper methods for [VaadinServlet] and generally for handling
 * [VaadinRequests][VaadinRequest].
 *
 * @since 1.0
 */
object HandlerHelper : Serializable {
    /**
     * The default SystemMessages (read-only).
     */
    //    static final SystemMessages DEFAULT_SYSTEM_MESSAGES = new SystemMessages();  // NOTE: not compatible with how we pull out this class

    /**
     * The pattern of error message shown when the URL path contains unsafe
     * double encoding.
     */
    const val UNSAFE_PATH_ERROR_MESSAGE_PATTERN = "Blocked attempt to access file: {}"
    private val PARENT_DIRECTORY_REGEX = Pattern
        .compile("(/|\\\\)\\.\\.(/|\\\\)?", Pattern.CASE_INSENSITIVE)

    /**
     * Returns whether the given request is of the given type.
     *
     * @param request
     * the request to check
     * @param requestType
     * the type to check for
     * @return `true` if the request is of the given type,
     * `false` otherwise
     */
    fun isRequestType(
        request: VaadinRequest,
        requestType: RequestType
    ): Boolean {
        return requestType.identifier == request
            .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER)
    }

    /**
     * Helper to find the most most suitable Locale. These potential sources are
     * checked in order until a Locale is found:
     *
     *  1. The passed component (or UI) if not null
     *  1. [UI.getCurrent] if defined
     *  1. The passed session if not null
     *  1. [VaadinSession.getCurrent] if defined
     *  1. The passed request if not null
     *  1. [VaadinService.getCurrentRequest] if defined
     *  1. [Locale.getDefault]
     *
     *
     * @param session
     * the session that is searched for locale or `null`
     * if not available
     * @param request
     * the request that is searched for locale or `null`
     * if not available
     * @return the found locale
     */
    fun findLocale(
        session: VaadinSession?,
        request: VaadinRequest?
    ): Locale {
        var session = session
        var request = request
        if (session == null) {
            session = VaadinSession.getCurrent()
        }
        if (session != null) {
            val locale = session.locale
            if (locale != null) {
                return locale
            }
        }
        if (request == null) {
            request = VaadinService.getCurrentRequest()
        }
        if (request != null) {
            val locale = request.locale
            if (locale != null) {
                return locale
            }
        }
        return Locale.getDefault()
    }

    /**
     * Sets no cache headers to the specified response.
     *
     * @param headerSetter
     * setter for string value headers
     * @param longHeaderSetter
     * setter for long value headers
     */
    fun setResponseNoCacheHeaders(
        headerSetter: BiConsumer<String?, String?>,
        longHeaderSetter: BiConsumer<String?, Long?>
    ) {
        headerSetter.accept("Cache-Control", "no-cache, no-store")
        headerSetter.accept("Pragma", "no-cache")
        longHeaderSetter.accept("Expires", 0L)
    }

    /**
     * Gets a relative path that cancels the provided path. This essentially
     * adds one .. for each part of the path to cancel.
     *
     * @param pathToCancel
     * the path that should be canceled
     * @return a relative path that cancels out the provided path segment
     */
    fun getCancelingRelativePath(pathToCancel: String): String {
        val sb = StringBuilder(".")
        // Start from i = 1 to ignore first slash
        for (i in 1 until pathToCancel.length) {
            if (pathToCancel[i] == '/') {
                sb.append("/..")
            }
        }
        return sb.toString()
    }

    /**
     * Checks if the given URL path contains the directory change instruction
     * (dot-dot), taking into account possible double encoding in hexadecimal
     * format, which can be injected maliciously.
     *
     * @param path
     * the URL path to be verified.
     * @return `true`, if the given path has a directory change
     * instruction, `false` otherwise.
     */
    fun isPathUnsafe(path: String?): Boolean {
        // Check that the path does not have '/../', '\..\', %5C..%5C,
        // %2F..%2F, nor '/..', '\..', %5C.., %2F..
        var path = path
        path = try {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (e: UnsupportedEncodingException) {
            throw RuntimeException(
                "An error occurred during decoding URL.",
                e
            )
        }
        return PARENT_DIRECTORY_REGEX.matcher(path).find()
    }

    /**
     * Framework internal enum for tracking the type of a request.
     */
    enum class RequestType(
        /**
         * Returns the identifier for the request type.
         *
         * @return the identifier
         */
        val identifier: String
    ) {
        /**
         * UIDL requests.
         */
        UIDL(ApplicationConstants.REQUEST_TYPE_UIDL),

        /**
         * Heartbeat requests.
         */
        HEARTBEAT(ApplicationConstants.REQUEST_TYPE_HEARTBEAT),

        /**
         * Push requests (any transport).
         */
        PUSH(ApplicationConstants.REQUEST_TYPE_PUSH);

    }
}
