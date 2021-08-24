package dorkbox.vaadin.util

/**
 *
 */
object CallingClass {
    fun get(): Class<*> {
        // StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass()
        return ClassUtils.forName(Thread.currentThread().stackTrace[3].className)
    }
}
