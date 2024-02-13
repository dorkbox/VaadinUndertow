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
package dorkbox.vaadin.util

import dorkbox.fsm.DoubleArrayStringTrie
import org.slf4j.Logger
import java.net.URL
import java.net.URLClassLoader

/**
 * Used to get classloader data, but using a trie to store the lookups
 */
class TrieClassLoader(
    private val diskTrie: DoubleArrayStringTrie<URL>,
    private val jarTrie: DoubleArrayStringTrie<String>,

    jarResourceDirs: Array<URL>, parentClassloader: ClassLoader,

    private val logger: Logger,
    private val loggerDisk: Logger,
    private val loggerJar: Logger,

    ): URLClassLoader(jarResourceDirs, parentClassloader) {

    override fun getResource(name: String): URL? {
        logger.trace(name)

        // check disk first
        val diskResourcePath: URL? = diskTrie[name]
        if (diskResourcePath != null) {
            loggerDisk.trace(diskResourcePath.file)
            return diskResourcePath
        }

        val jarResourcePath: String? = jarTrie[name]
        if (jarResourcePath != null) {
            loggerJar.trace(jarResourcePath)
            return super.getResource(jarResourcePath)
        }

        return super.getResource(name)
    }
}
