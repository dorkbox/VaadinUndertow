package dorkbox.vaadin.undertow

import java.net.URL

data class WebResourceString(val requestPath: String, val resourcePath: URL, val relativeResourcePath: String)
