package starx.dev.agentbridge

import java.security.*
import java.security.cert.X509Certificate
import java.util.concurrent.*
import javax.net.ssl.*
import org.junit.Assert.*
import org.junit.Test

class CertificateTest {
  @Test
  fun selfSignedIdentityAndSanAreValid() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val cert = Certificates.create(key, listOf("127.0.0.1", "phone.example")) as X509Certificate
    cert.checkValidity()
    cert.verify(key.public)
    val names = cert.subjectAlternativeNames.map { it[1] }
    assertTrue(names.contains("127.0.0.1"))
    assertTrue(names.contains("phone.example"))
    assertEquals("RSA", cert.publicKey.algorithm)
  }

  @Test
  fun tlsAcceptsExplicitCaAndChecksHost() {
    val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val cert = Certificates.create(key, listOf("localhost")) as X509Certificate
    val store =
      KeyStore.getInstance("PKCS12").apply {
        load(null)
        setKeyEntry("tls", key.private, "password".toCharArray(), arrayOf(cert))
      }
    val km =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(store, "password".toCharArray())
      }
    val trust =
      KeyStore.getInstance("PKCS12").apply {
        load(null)
        setCertificateEntry("ca", cert)
      }
    val tm =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(trust)
      }
    val serverContext = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
    val clientContext = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
    val server = serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    val pool = Executors.newSingleThreadExecutor()
    fun handshake(host: String): Boolean {
      val task =
        pool.submit {
          runCatching {
            (server.accept() as SSLSocket).use {
              it.soTimeout = 2000
              it.startHandshake()
            }
          }
        }
      val ok =
        runCatching {
            val transport = java.net.Socket("127.0.0.1", server.localPort)
            (clientContext.socketFactory.createSocket(transport, host, server.localPort, true)
                as SSLSocket)
              .use { socket ->
                socket.soTimeout = 2000
                socket.sslParameters =
                  socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
              }
          }
          .isSuccess
      task.get(3, TimeUnit.SECONDS)
      return ok
    }
    try {
      assertTrue(handshake("localhost"))
      assertFalse(handshake("wrong.example"))
    } finally {
      server.close()
      pool.shutdownNow()
    }
  }
}
