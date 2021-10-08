/*
 * Copyright 2021 dorkbox, llc
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

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * @param threadGroup the thread-group to assign, otherwise null (which will use the current thread's threadGroup)
 */
class DaemonThreadFactory(private val threadName: String, private val threadGroup: ThreadGroup? = null) : ThreadFactory {
    private val threadCount = AtomicInteger(0)

    override fun newThread(r: Runnable): Thread {
        val thread: Thread = if (threadGroup != null) {
            Thread(threadGroup, r)
        } else {
            Thread(r)
        }

        thread.isDaemon = true
        thread.name = threadName + "-" + threadCount.getAndIncrement()

        return thread
    }
}
