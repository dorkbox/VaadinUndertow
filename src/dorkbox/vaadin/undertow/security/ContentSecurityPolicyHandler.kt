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

object ContentSecurityPolicyHandler {
    private val CSP_HEADER = "Content-Security-Policy"

    enum class ContentSecurityPolicy constructor(val value: String) {
        /** blocks the use of this type of resource. */
        NONE("'none'"),

        /** matches the current origin (but not subdomains). */
        SELF("'self'"),

        /** allows the use of inline JS and CSS. */
        UNSAFE_INLINE("'unsafe-inline'"),

        /** allows the use of mechanisms like eval(). */
        UNSAFE_EVAL("'unsafe-eval'")
    }


    // https://scotthelme.co.uk/content-security-policy-an-introduction/#whatcanweprotect
    class Builder {
        private val policyMap: MutableMap<String, String> = mutableMapOf()

        /** Define loading policy for all resources type in case of a resource type dedicated directive is not defined (fallback) */
        fun defaultSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["default-src"] = policy.value
            return this
        }

        /** Define loading policy for all resources type in case of a resource type dedicated directive is not defined (fallback) */
        fun defaultSrc(vararg policies: String): Builder {
            policyMap["default-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define which scripts the protected resource can execute */
        fun scriptSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["script-src"] = policy.value
            return this
        }

        /** Define which scripts the protected resource can execute */
        fun scriptSrc(vararg policies: String): Builder {
            policyMap["script-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define from where the protected resource can load plugins */
        fun objectSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["object-src"] = policy.value
            return this
        }

        /** Define from where the protected resource can load plugins */
        fun objectSrc(vararg policies: String): Builder {
            policyMap["object-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define which styles (CSS) the user applies to the protected resource */
        fun styleSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["style-src"] = policy.value
            return this
        }

        /** Define which styles (CSS) the user applies to the protected resource */
        fun styleSrc(vararg policies: String): Builder {
            policyMap["style-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define from where the protected resource can load images */
        fun imgSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["img-src"] = policy.value
            return this
        }

        /** Define from where the protected resource can load images */
        fun imgSrc(vararg policies: String): Builder {
            policyMap["img-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define from where the protected resource can load video and audio */
        fun mediaSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["media-src"] = policy.value
            return this
        }

        /** Define from where the protected resource can load video and audio */
        fun mediaSrc(vararg policies: String): Builder {
            policyMap["media-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define from where the protected resource can embed frames */
        fun frameSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["frame-src"] = policy.value
            return this
        }

        /** Define from where the protected resource can embed frames */
        fun frameSrc(vararg policies: String): Builder {
            policyMap["frame-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define from where the protected resource can load fonts */
        fun fontSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["font-src"] = policy.value
            return this
        }

        /** Define from where the protected resource can load fonts */
        fun fontSrc(vararg policies: String): Builder {
            policyMap["font-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define which URIs the protected resource can load using script interfaces */
        fun connectSrc(policy: ContentSecurityPolicy): Builder {
            policyMap["connect-src"] = policy.value
            return this
        }

        /** Define which URIs the protected resource can load using script interfaces */
        fun connectSrc(vararg policies: String): Builder {
            policyMap["connect-src"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define which URIs can be used as the action of HTML form elements */
        fun formAction(policy: ContentSecurityPolicy): Builder {
            policyMap["form-action"] = policy.value
            return this
        }

        /** Define which URIs can be used as the action of HTML form elements */
        fun formAction(vararg policies: String): Builder {
            policyMap["form-action"] = policies.joinToString(separator = " ")
            return this
        }

        /** Specifies an HTML sandbox policy that the user agent applies to the protected resource */
        fun sandbox(policy: ContentSecurityPolicy): Builder {
            policyMap["sandbox"] = policy.value
            return this
        }

        /** Specifies an HTML sandbox policy that the user agent applies to the protected resource */
        fun sandbox(vararg policies: String): Builder {
            policyMap["sandbox"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define script execution by requiring the presence of the specified nonce on script elements */
        fun scriptNonce(policy: ContentSecurityPolicy): Builder {
            policyMap["script-nonce"] = policy.value
            return this
        }

        /** Define script execution by requiring the presence of the specified nonce on script elements */
        fun scriptNonce(vararg policies: String): Builder {
            policyMap["script-nonce"] = policies.joinToString(separator = " ")
            return this
        }

        /** Define the set of plugins that can be invoked by the protected resource by limiting the types of resources that can be embedded */
        fun pluginTypes(policy: ContentSecurityPolicy): Builder {
            policyMap["plugin-types"] = policy.value
            return this
        }

        /** Define the set of plugins that can be invoked by the protected resource by limiting the types of resources that can be embedded */
        fun pluginTypes(vararg policies: String): Builder {
            policyMap["plugin-types"] = policies.joinToString(separator = " ")
            return this
        }

        /**
         * Instructs a user agent to activate or deactivate any heuristics used to filter or block reflected cross-site scripting attacks,
         * equivalent to the effects of the non-standard X-XSS-Protection header
         */
        fun reflectedXss(policy: ContentSecurityPolicy): Builder {
            policyMap["reflected-xss"] = policy.value
            return this
        }

        /**
         * Instructs a user agent to activate or deactivate any heuristics used to filter or block reflected cross-site scripting attacks,
         * equivalent to the effects of the non-standard X-XSS-Protection header
         */
        fun reflectedXss(vararg policies: String): Builder {
            policyMap["reflected-xss"] = policies.joinToString(separator = " ")
            return this
        }

        /** Specifies a URI to which the user agent sends reports about policy violation */
        fun reportUri(uri: String): Builder {
            policyMap["report-uri"] = uri
            return this
        }

        fun build(delegate: HttpHandler): HttpHandler {
            val policy = policyMap.entries.joinToString(separator = "; ") { it.key + " " + it.value }
            return SetHeaderHandler(delegate, CSP_HEADER, policy)
        }
    }
}
