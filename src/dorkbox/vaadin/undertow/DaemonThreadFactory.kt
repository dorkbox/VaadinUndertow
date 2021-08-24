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
