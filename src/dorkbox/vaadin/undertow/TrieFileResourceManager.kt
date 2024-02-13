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
import io.undertow.server.handlers.resource.*
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * [ResourceManager] for pre-scanned file resources.
 *
 * @author Dorkbox LLC
 */
internal class TrieFileResourceManager(val name: String,
                                       private val trie: DoubleArrayStringTrie<URL>,
                                       private val logger: Logger,


                                       base: File = File("").absoluteFile,

                                       /**
                                        * Size to use direct FS to network transfer (if supported by OS/JDK) instead of read/write
                                        */
                                       transferMinSize: Long = 1024,

                                       /**
                                        * Check to validate caseSensitive issues for specific case-insensitive FS.
                                        * @see io.undertow.server.handlers.resource.PathResourceManager#isFileSameCase(java.nio.file.Path, String)
                                        */
                                       caseSensitive: Boolean = false,

                                       /**
                                        * Check to allow follow symbolic links
                                        */
                                       followLinks: Boolean = false,

                                       /**
                                        * Used if followLinks == true. Set of paths valid to follow symbolic links. If this is empty and followLinks
                                        * it true then all links will be followed
                                        */
                                       vararg safePaths: String)

    : FileResourceManager(base, transferMinSize, caseSensitive, followLinks, *safePaths) {

    @Throws(IOException::class)
    override fun getResource(path: String): Resource? {
        val url = trie[path]
        if (url === null) {
            logger.trace("REQUEST not found for PATH: {}", path)
            return null
        }

        val file = File(url.file)
        val resource = FileResource(file, this, path)
        if (path.isNotBlank() && path != "/" && resource.contentLength < 0) {
            logger.trace("REQUEST file not found for PATH: {}, {}", path, file)
            return null
        }

        logger.debug("REQUEST found: {}, {}", path, file)
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
        return "FileResourceManager($name)"
    }
}
