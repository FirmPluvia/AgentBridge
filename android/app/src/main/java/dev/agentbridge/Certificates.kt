package dev.agentbridge

import java.math.BigInteger
import java.security.*
import java.util.Date
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*

object Certificates {
  fun create(kp: KeyPair, names: List<String>): java.security.cert.Certificate {
    val now = System.currentTimeMillis()
    val dn = X500Name("CN=AgentBridge")
    val algorithm =
      AlgorithmIdentifier(ASN1ObjectIdentifier("1.2.840.113549.1.1.11"), DERNull.INSTANCE)
    val builder =
      V3TBSCertificateGenerator().apply {
        setSerialNumber(ASN1Integer(BigInteger(128, SecureRandom())))
        setSignature(algorithm)
        setIssuer(dn)
        setSubject(dn)
        setStartDate(Time(Date(now - 86400000)))
        setEndDate(Time(Date(now + 10L * 365 * 86400000)))
        setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.public.encoded))
      }
    val extensions = ExtensionsGenerator()
    if (names.isNotEmpty()) {
      val sans =
        names
          .distinct()
          .map {
            GeneralName(
              if (it.contains(':') || Regex("[0-9.]+").matches(it)) GeneralName.iPAddress
              else GeneralName.dNSName,
              it,
            )
          }
          .toTypedArray()
      extensions.addExtension(
        org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
        false,
        GeneralNames(sans),
      )
    }
    extensions.addExtension(
      org.bouncycastle.asn1.x509.Extension.basicConstraints,
      true,
      BasicConstraints(false),
    )
    builder.setExtensions(extensions.generate())
    val tbs = builder.generateTBSCertificate()
    val signer =
      Signature.getInstance("SHA256withRSA").apply {
        initSign(kp.private)
        update(tbs.encoded)
      }
    val encoded =
      DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign()))).encoded
    return java.security.cert.CertificateFactory.getInstance("X.509")
      .generateCertificate(encoded.inputStream())
  }
}
