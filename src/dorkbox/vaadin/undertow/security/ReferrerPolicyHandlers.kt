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

object ReferrerPolicyHandlers {
    private val REFERRER_POLICY_STRING = "Referrer-Policy"


    // See https://scotthelme.co.uk/a-new-security-header-referrer-policy/
    enum class ReferrerPolicy constructor(val value: String) {
        EMPTY(""),
        NO_REFERRER("no-referrer"),
        NO_REFERRER_WHEN_DOWNGRADE("no-referrer-when-downgrade"),
        SAME_ORIGIN("same-origin"),
        ORIGIN("origin"),
        STRICT_ORIGIN("strict-origin"
                                                                                                                                                                    ),
        ORIGIN_WHEN_CROSS_ORIGIN("origin-when-cross-origin"),
        STRICT_ORIGIN_WHEN_CROSS_ORIGIN("strict-origin-when-cross-origin"),
        UNSAFE_URL("unsafe-url")
    }

    fun policy(next: HttpHandler, policy: ReferrerPolicy): HttpHandler {
        return SetHeaderHandler(next, REFERRER_POLICY_STRING, policy.value)
    }
}
