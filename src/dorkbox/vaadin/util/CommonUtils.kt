/*
 * Copyright 2017 Pronghorn Technology LLC
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

import mu.KLogger
import mu.KotlinLogging
import java.io.PrintWriter
import java.io.StringWriter

inline fun <reified T> T.logger(): KLogger {
    if (T::class.isCompanion) {
        return KotlinLogging.logger(T::class.java.enclosingClass.name)
    }
    return KotlinLogging.logger(T::class.java.name)
}


fun Exception.stackTraceToString(): String {
    val exceptionWriter = StringWriter()
    printStackTrace(PrintWriter(exceptionWriter))
    return exceptionWriter.toString()
}

inline fun ignoreException(block: () -> Unit) {
    try {
        block()
    } catch (ex: Exception) {
        // no-op
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun ignoreExceptions(vararg blocks: () -> Unit) {
    blocks.forEach { block ->
        try {
            block()
        } catch (ex: Exception) {
            // no-op
        }
    }
}
