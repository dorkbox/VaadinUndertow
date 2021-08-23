/**
 * MIT License
 *
 * Copyright (c) 2017 StubbornJava
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dorkbox.vaadin.undertow.security

import io.undertow.server.HttpHandler
import io.undertow.server.handlers.SetHeaderHandler
import io.undertow.util.Headers

/**
 * HTTP Strict Transport Security (HSTS) is a policy mechanism that allows a web server to enforce the use of TLS in a compliant User Agent (UA), such as a
 * web browser. HSTS allows for a more effective implementation of TLS by ensuring all communication takes place over a secure transport layer on the
 * client side.
 *
 * Most notably HSTS mitigates variants of man in the middle (MiTM) attacks where TLS can be stripped out of communications with a server, leaving a user
 * vulnerable to further risk.
 */
object StrictTransportSecurityHandlers {

    fun hsts(next: HttpHandler, maxAge: Long): HttpHandler {
        return SetHeaderHandler(next, Headers.STRICT_TRANSPORT_SECURITY_STRING, "max-age=$maxAge")
    }

    fun hstsIncludeSubdomains(next: HttpHandler, maxAge: Long): HttpHandler {
        return SetHeaderHandler(next, Headers.STRICT_TRANSPORT_SECURITY_STRING, "max-age=$maxAge; includeSubDomains")
    }
}
