package dorkbox.vaadin.util

import java.util.*

/**
 *
 */
object CallingClass {
    fun get(): Class<*> {
        val caller: Optional<Class<*>> = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                .skip(3)
                .findFirst()
        }

        return caller.get()
    }
}
