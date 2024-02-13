/*
 * Copyright 2024 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

import dorkbox.fsm.DoubleArrayStringTrie
import io.undertow.UndertowMessages
import io.undertow.server.handlers.resource.Resource
import io.undertow.server.handlers.resource.ResourceChangeListener
import io.undertow.server.handlers.resource.ResourceManager
import io.undertow.server.handlers.resource.URLResource
import org.slf4j.Logger
import java.io.IOException
import java.net.URL

/**
 * [ResourceManager] for JAR resources.
 *
 * @author Ivan Sopov
 * @author Andy Wilkinson
 * @author Dorkbox LLC
 */
internal class JarResourceManager(val name: String,
                                  private val trie: DoubleArrayStringTrie<URL>,
                                  private val logger: Logger) : ResourceManager {

    @Throws(IOException::class)
    override fun getResource(path: String): Resource? {
        val url = trie[path]
        if (url === null) {
            logger.trace("REQUEST not found for PATH: {}", path)
            return null
        }

        val resource = URLResource(url, path)
        if (path.isNotBlank() && path != "/" && resource.contentLength < 0) {
            logger.trace("REQUEST file not found for PATH: {}, {}", path, url)
            return null
        }

        logger.debug("REQUEST found: {}, {}", path, url)
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
