package dorkbox.vaadin.util

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class DummyTrustManager : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }

    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
}
