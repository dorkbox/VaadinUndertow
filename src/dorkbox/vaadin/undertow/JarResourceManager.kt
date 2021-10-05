/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.vaadin.undertow

import dorkbox.vaadin.util.ahoCorasick.DoubleArrayTrie
import io.undertow.UndertowMessages
import io.undertow.server.handlers.resource.Resource
import io.undertow.server.handlers.resource.ResourceChangeListener
import io.undertow.server.handlers.resource.ResourceManager
import io.undertow.server.handlers.resource.URLResource
import java.io.IOException
import java.net.URL

/**
 * [ResourceManager] for JAR resources.
 *
 * @author Ivan Sopov
 * @author Andy Wilkinson
 * @author Dorkbox LLC
 */
internal class JarResourceManager(val name: String, val trie: DoubleArrayTrie<URL>, val debug: Boolean) : ResourceManager {

    @Throws(IOException::class)
    override fun getResource(path: String): Resource? {
        if (debug) {
            println("REQUEST jar: $path")
        }

        val url = trie[path] ?: return null

        if (debug) {
            println("TRIE: $url")
        }

        val resource = URLResource(url, path)
        if (path.isNotBlank() && path != "/" && resource.contentLength < 0) {
            return null
        }

        return resource
    }

    override fun isResourceChangeListenerSupported(): Boolean {
        return false
    }

    override fun registerResourceChangeListener(listener: ResourceChangeListener) {
        throw UndertowMessages.MESSAGES.resourceChangeListenerNotSupported()
    }

    override fun removeResourceChangeListener(listener: ResourceChangeListener) {
        throw UndertowMessages.MESSAGES.resourceChangeListenerNotSupported()
    }

    @Throws(IOException::class)
    override fun close() {
    }

    override fun toString(): String {
        return "JarResourceManager($name)"
    }
}
