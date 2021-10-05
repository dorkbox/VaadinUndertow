package dorkbox.vaadin.util

import dorkbox.vaadin.util.ahoCorasick.DoubleArrayTrie
import java.net.URL
import java.net.URLClassLoader

/**
 *
 */
class TrieClassLoader(
    private val urlTrie: DoubleArrayTrie<URL>,
    private val stringTrie: DoubleArrayTrie<String>,
    urls: Array<URL>, parent: ClassLoader,
    private val debug: Boolean = false): URLClassLoader(urls, parent) {

    override fun getResource(name: String): URL? {
        if (debug) {
            println(" URL Classloader: $name")
        }

        // check disk first
        val diskResourcePath: URL? = urlTrie[name]
        if (diskResourcePath != null) {
            if (debug) {
                println("TRIE: $diskResourcePath")
            }
            return diskResourcePath
        }

        val jarResourcePath: String? = stringTrie[name]
        if (jarResourcePath != null) {
            if (debug) {
                println("TRIE: $jarResourcePath")
            }
            return super.getResource(jarResourcePath)
        }

        return super.getResource(name)
    }
}
