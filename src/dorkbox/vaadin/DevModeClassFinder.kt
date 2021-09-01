package dorkbox.vaadin

import com.vaadin.flow.server.frontend.scanner.ClassFinder
import java.util.*
import javax.servlet.annotation.HandlesTypes

internal class DevModeClassFinder(classes: Set<Class<*>?>?) : ClassFinder.DefaultClassFinder(classes) {
        companion object {
            private val APPLICABLE_CLASS_NAMES = Collections.unmodifiableSet(calculateApplicableClassNames())

            private fun calculateApplicableClassNames(): Set<String> {
                val handlesTypes: HandlesTypes = DevModeInitializer::class.java.getAnnotation(HandlesTypes::class.java)
                return handlesTypes.value.map { it.qualifiedName!! }.toSet()
            }
        }

        override fun getAnnotatedClasses(annotation: Class<out Annotation?>): Set<Class<*>> {
            ensureImplementation(annotation)
            return super.getAnnotatedClasses(annotation)
        }

        override fun <T> getSubTypesOf(type: Class<T>): Set<Class<out T>> {
            ensureImplementation(type)
            return super.getSubTypesOf(type)
        }

        private fun ensureImplementation(clazz: Class<*>) {
            require(APPLICABLE_CLASS_NAMES.contains(clazz.name)) {
                ("Unexpected class name "
                        + clazz + ". Implementation error: the class finder "
                        + "instance is not aware of this class. "
                        + "Fix @HandlesTypes annotation value for "
                        + DevModeInitializer::class.java.name)
            }
        }
    }
