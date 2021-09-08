//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//
package dorkbox.vaadin.devMode

import com.vaadin.flow.server.VaadinRequest
import com.vaadin.flow.server.VaadinService
import com.vaadin.flow.server.VaadinSession
import java.io.Serializable
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.BiConsumer
import java.util.regex.Pattern

/**
 * THIS IS COPIED DIRECTLY FROM VAADIN 14.6.8 (flow 2.4.6)
 *
 * CHANGES FROM DEFAULT ARE MANAGED AS DIFFERENT REVISIONS.
 *
 * The initial commit is exactly as-is from vaadin.
 *
 * This file is NOT extensible/configurable AT-ALL, so this is required...
 */
object HandlerHelper : Serializable {
    //    static final SystemMessages DEFAULT_SYSTEM_MESSAGES = new SystemMessages();
    const val UNSAFE_PATH_ERROR_MESSAGE_PATTERN = "Blocked attempt to access file: {}"
    private val PARENT_DIRECTORY_REGEX = Pattern.compile("(/|\\\\)\\.\\.(/|\\\\)?", 2)
    fun isRequestType(request: VaadinRequest, requestType: RequestType): Boolean {
        return requestType.identifier == request.getParameter("v-r")
    }

    fun findLocale(session: VaadinSession?, request: VaadinRequest?): Locale {
        var session = session
        var request = request
        if (session == null) {
            session = VaadinSession.getCurrent()
        }
        var locale: Locale?
        if (session != null) {
            locale = session.locale
            if (locale != null) {
                return locale
            }
        }
        if (request == null) {
            request = VaadinService.getCurrentRequest()
        }
        if (request != null) {
            locale = request.locale
            if (locale != null) {
                return locale
            }
        }
        return Locale.getDefault()
    }

    fun setResponseNoCacheHeaders(headerSetter: BiConsumer<String?, String?>, longHeaderSetter: BiConsumer<String?, Long?>) {
        headerSetter.accept("Cache-Control", "no-cache, no-store")
        headerSetter.accept("Pragma", "no-cache")
        longHeaderSetter.accept("Expires", 0L)
    }

    fun getCancelingRelativePath(pathToCancel: String): String {
        val sb = StringBuilder(".")
        for (i in 1 until pathToCancel.length) {
            if (pathToCancel[i] == '/') {
                sb.append("/..")
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun isPathUnsafe(path: String?): Boolean {
        var path = path
        path = try {
            URLDecoder.decode(path, StandardCharsets.UTF_8.name())
        } catch (var2: UnsupportedEncodingException) {
            throw RuntimeException("An error occurred during decoding URL.", var2)
        }
        return PARENT_DIRECTORY_REGEX.matcher(path).find()
    }

    enum class RequestType(val identifier: String) {
        UIDL("uidl"), HEARTBEAT("heartbeat"), PUSH("push");

    }
}
