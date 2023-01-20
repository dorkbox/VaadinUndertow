package dorkbox.vaadin.undertow

import io.undertow.server.HttpHandler
import java.util.function.Function

class HandleBuilder private constructor(private val function: Function<HttpHandler, HttpHandler>) {

    companion object {
        fun begin(function: Function<HttpHandler, HttpHandler>): HandleBuilder {
            return HandleBuilder(function)
        }
    }

    fun next(before: Function<HttpHandler, HttpHandler>): HandleBuilder {
        return HandleBuilder(function.compose(before))
    }

    fun complete(handler: HttpHandler): HttpHandler {
        return function.apply(handler)
    }
}
