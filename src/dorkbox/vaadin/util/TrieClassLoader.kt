/*
 * Copyright 2023 dorkbox, llc
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
package dorkbox.vaadin.util

import dorkbox.fsm.DoubleArrayStringTrie
import mu.KLogger
import java.net.URL
import java.net.URLClassLoader

/**
 *
 */
class TrieClassLoader(
    private val urlTrie: DoubleArrayStringTrie<URL>,
    private val stringTrie: DoubleArrayStringTrie<String>,
    urls: Array<URL>, parent: ClassLoader,
    private val logger: KLogger): URLClassLoader(urls, parent) {

    override fun getResource(name: String): URL? {
        logger.trace { "URL Classloader: $name" }

        // check disk first
        val diskResourcePath: URL? = urlTrie[name]
        if (diskResourcePath != null) {
            logger.trace { "TRIE: $diskResourcePath" }
            return diskResourcePath
        }

        val jarResourcePath: String? = stringTrie[name]
        if (jarResourcePath != null) {
            logger.trace { "TRIE: $jarResourcePath" }
            return super.getResource(jarResourcePath)
        }

        return super.getResource(name)
    }
}
