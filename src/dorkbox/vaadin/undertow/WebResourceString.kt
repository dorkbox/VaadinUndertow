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
package dorkbox.vaadin.undertow

import java.net.URL

// stored in a set, and URL.toString() can cause a DNS lookup!
data class WebResourceString(val requestPath: String, val resourcePath: URL, val relativeResourcePath: String, val resourceDir: URL) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebResourceString) return false

        if (requestPath != other.requestPath) return false
        if (relativeResourcePath != other.relativeResourcePath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = requestPath.hashCode()
        result = 31 * result + relativeResourcePath.hashCode()
        return result
    }

    override fun toString(): String {
        return "WebResourceString(requestPath='$requestPath', relativeResourcePath='$relativeResourcePath')"
    }
}
