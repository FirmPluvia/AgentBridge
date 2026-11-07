package dev.agentbridge

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BridgeApp : Application() {
  override fun onCreate() {
    super.onCreate()
    State.init(this)
  }
}

object State {
  lateinit var context: Context
  lateinit var vault: Vault
  lateinit var engine: Engine
  @Volatile var running = false
  @Volatile var listener = "未启动"
  @Volatile var servicePhase = "stopped"
  @Volatile var lastError = ""
  private val _runtime = MutableStateFlow(RuntimeSnapshot())
  val runtime = _runtime.asStateFlow()
  private val _audit = MutableStateFlow<List<String>>(emptyList())
  val audit = _audit.asStateFlow()
  @Volatile private var controllerToken: String? = null
  val logs = CopyOnWriteArrayList<String>()

  fun init(c: Context) {
    context = c
    vault = Vault(c)
    c.getSharedPreferences("vault", 0)
      .edit()
      .remove("adb")
      .remove("adbPins")
      .remove("pairCode")
      .apply()
    if (pref("backend") == "adb") {
      put("backend", "none")
      put("shell", "false")
    }
    val oldAddress = pref("accessHost")
    if (oldAddress.isNotEmpty()) {
      val extra =
        (pref("tlsExtraNames").split(',') + oldAddress)
          .filter { ConnectionSettings.validHost(it) }
          .distinct()
          .joinToString(",")
      put("tlsExtraNames", extra)
      c.getSharedPreferences("config", 0).edit().remove("accessHost").apply()
    }
    c.getSharedPreferences("config", 0).edit().remove("shellBackend").apply()
    put("mode", "direct")
    engine = Engine(c)
    publish()
  }

  fun log(s: String) {
    logs.add("${java.time.Instant.now()} $s")
    while (logs.size > 300) logs.removeAt(0)
    _audit.value = logs.toList()
  }

  fun pref(k: String, default: String = "") =
    context.getSharedPreferences("config", 0).getString(k, default)!!

  fun put(k: String, v: String) {
    context.getSharedPreferences("config", 0).edit().putString(k, v).apply()
  }

  val deviceId: String
    get() {
      var id = pref("deviceId")
      if (id.isEmpty()) {
        id = UUID.randomUUID().toString()
        put("deviceId", id)
      }
      return id
    }

  @Synchronized
  fun token(): String {
    controllerToken?.let {
      return it
    }
    var t = vault.get("controller")
    if (t.isEmpty()) {
      t = secret()
      vault.put("controller", t)
    }
    controllerToken = t
    return t
  }

  fun rotate() {
    val next = secret()
    vault.put("controller", next)
    controllerToken = next
    engine.stopAll()
    log("控制凭证已轮换，所有会话失效")
  }

  fun publish() {
    if (!::engine.isInitialized) return
    val selected = engine.selected
    val backendUid =
      when (selected.name) {
        "root" -> engine.root.uid
        "shizuku" -> engine.shizuku.uid
        else -> -1
      }
    val connected =
      when (selected.name) {
        "root" -> engine.root.connected
        "shizuku" -> engine.shizuku.connected
        else -> false
      }
    _runtime.value =
      RuntimeSnapshot(
        running,
        servicePhase,
        listener,
        selected.name,
        selected.status(),
        engine.root.status(),
        engine.shizuku.status(),
        engine.session,
        lastError,
        backendUid,
        connected,
      )
  }

  fun secret(): String {
    val b = ByteArray(32)
    SecureRandom().nextBytes(b)
    return Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE)
  }
}

class Vault(c: Context) {
  private val prefs = c.getSharedPreferences("vault", 0)
  private val identities = IdentityCache<KeyStore>()
  private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  private fun key(): SecretKey {
    if (!ks.containsAlias("vault"))
      KeyGenerator.getInstance("AES", "AndroidKeyStore")
        .apply {
          init(
            KeyGenParameterSpec.Builder(
                "vault",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
              )
              .setBlockModes("GCM")
              .setEncryptionPaddings("NoPadding")
              .build()
          )
        }
        .generateKey()
    return ks.getKey("vault", null) as SecretKey
  }

  @Synchronized
  fun put(k: String, v: String) {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key())
    prefs
      .edit()
      .putString(k, Base64.encodeToString(c.iv + c.doFinal(v.toByteArray()), Base64.NO_WRAP))
      .commit()
  }

  @Synchronized
  fun get(k: String): String {
    val s = prefs.getString(k, null) ?: return ""
    val b = Base64.decode(s, Base64.NO_WRAP)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
    return c.doFinal(b.copyOfRange(12, b.size)).toString(Charsets.UTF_8)
  }

  fun resetIdentity(name: String) {
    identities.invalidate(name) { put(name, "") }
  }

  fun identity(name: String): KeyStore = identities.get(name) { loadIdentity(name) }

  private fun loadIdentity(name: String): KeyStore {
    val password = "local-encrypted-vault".toCharArray()
    val stored = get(name)
    val store = KeyStore.getInstance("PKCS12")
    if (stored.isNotEmpty()) {
      store.load(Base64.decode(stored, Base64.NO_WRAP).inputStream(), password)
      return store
    }
    store.load(null, password)
    val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val names =
      if (name == "tls")
        (State.pref("tlsNames", "localhost,127.0.0.1") + "," + State.pref("bind", "127.0.0.1"))
          .split(',')
          .map { it.trim() }
          .filter { it.isNotEmpty() && it != "0.0.0.0" && it != "::" }
      else emptyList()
    val cert = Certificates.create(kp, names)
    store.setKeyEntry(name, kp.private, password, arrayOf(cert))
    val out = java.io.ByteArrayOutputStream()
    store.store(out, password)
    put(name, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    return store
  }
}

class BridgeError(val code: String, override val message: String, val retryable: Boolean = false) :
  Exception(message)

fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

fun argvCommand(a: List<String>) = a.joinToString(" ") { quote(it) }
