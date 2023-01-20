package dorkbox.vaadin.undertow

import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.StatusCodes

object Redirect {
    fun temporary(exchange: HttpServerExchange, location: String) {
        exchange.statusCode = StatusCodes.FOUND
        exchange.responseHeaders.put(Headers.LOCATION, location)
        exchange.endExchange()
    }

    fun permanent(exchange: HttpServerExchange, location: String) {
        exchange.statusCode = StatusCodes.MOVED_PERMANENTLY
        exchange.responseHeaders.put(Headers.LOCATION, location)
        exchange.endExchange()
    }

    fun referer(exchange: HttpServerExchange) {
        exchange.statusCode = StatusCodes.FOUND
        exchange.responseHeaders.put(Headers.LOCATION, exchange.requestHeaders[Headers.REFERER, 0])
        exchange.endExchange()
    }
}
