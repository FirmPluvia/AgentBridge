package starx.dev.agentbridge

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

class ExecResult(
  val stdout: ByteArray,
  val stderr: ByteArray,
  val exitCode: Int,
  val state: String = "completed",
)

interface ExecutionBackend {
  val name: String

  fun execute(
    argv: List<String>,
    token: String,
    timeout: Int = 10000,
    limit: Int = 1048576,
  ): ExecResult

  fun cancel(token: String)

  fun status(): String
}

object Processes {
  val active = ConcurrentHashMap<String, java.lang.Process>()
  val cancelled = ConcurrentHashMap.newKeySet<String>()

  fun run(argv: List<String>, token: String, timeout: Int, limit: Int): ExecResult {
    val p = ProcessBuilder(argv).start()
    active[token] = p
    val exceeded = AtomicBoolean(false)
    val totalRead = java.util.concurrent.atomic.AtomicInteger()
    fun collect(input: InputStream): ByteArray {
      val out = ByteArrayOutputStream()
      val buf = ByteArray(8192)
      while (true) {
        val n =
          try {
            input.read(buf)
          } catch (e: IOException) {
            if (cancelled.contains(token) || exceeded.get() || !p.isAlive) break else throw e
          }
        if (n < 0) break
        if (totalRead.addAndGet(n) > limit) {
          exceeded.set(true)
          p.destroyForcibly()
          break
        }
        out.write(buf, 0, n)
      }
      return out.toByteArray()
    }
    val pool = Executors.newFixedThreadPool(2)
    val stdout = pool.submit<ByteArray> { collect(p.inputStream) }
    val stderr = pool.submit<ByteArray> { collect(p.errorStream) }
    try {
      if (!p.waitFor(timeout.toLong(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly()
        throw BridgeError("TIMEOUT", "执行超时；副作用可能已发生")
      }
      fun drained(future: Future<ByteArray>): ByteArray =
        try {
          future.get(2, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
          if (cancelled.contains(token) || exceeded.get()) ByteArray(0) else throw e
        }
      val a = drained(stdout)
      val b = drained(stderr)
      if (cancelled.contains(token)) throw BridgeError("CANCELLED", "进程已请求终止，副作用可能已发生")
      if (exceeded.get()) throw BridgeError("OUTPUT_LIMIT_EXCEEDED", "输出超过限制")
      return ExecResult(a, b, p.exitValue())
    } finally {
      active.remove(token)
      cancelled.remove(token)
      p.destroyForcibly()
      pool.shutdownNow()
    }
  }

  fun cancel(token: String) {
    active[token]?.let {
      cancelled.add(token)
      it.destroyForcibly()
    }
  }
}

class UnavailableBackend : ExecutionBackend {
  override val name = "none"

  override fun status() = "请选择并授权 Root 或 Shizuku"

  override fun cancel(token: String) {}

  override fun execute(argv: List<String>, token: String, timeout: Int, limit: Int): ExecResult =
    throw BridgeError("BACKEND_UNAVAILABLE", status())
}

class RootBackend(private val context: Context) : ExecutionBackend {
  override val name = "root"
  @Volatile var uid = -1
  @Volatile var shellUid = -1
  @Volatile var detail = "尚未授权；请在权限页申请连接"
  @Volatile private var remote: IExecutor? = null
  val connected: Boolean
    get() = remote?.asBinder()?.isBinderAlive == true

  private val handler = Handler(Looper.getMainLooper())
  @Volatile private var pending: CompletableFuture<Int>? = null
  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        try {
          val service = IExecutor.Stub.asInterface(binder)
          val actual = service.uid()
          if (actual != 0) throw BridgeError("PERMISSION_DENIED", "特权服务实际 UID=$actual，拒绝使用")
          remote = service
          uid = actual
          detail = "Root 服务已连接 · UID=0"
          binder.linkToDeath({ lost("Root 服务已退出，请重新授权连接") }, 0)
          pending?.complete(actual)
          State.publish()
        } catch (e: Exception) {
          lost("Root 服务连接失败：${e.message}")
        }
      }

      override fun onServiceDisconnected(name: ComponentName) {
        lost("Root 服务已断开，请重新连接")
      }
    }

  private fun lost(reason: String) {
    remote = null
    uid = -1
    detail = reason
    pending?.completeExceptionally(BridgeError("BACKEND_UNAVAILABLE", reason))
    State.publish()
  }

  @Synchronized
  fun verify(): Int {
    val active = remote
    if (active != null && active.asBinder().isBinderAlive) {
      val check = readRemote(active, listOf("/system/bin/id", "-u"), "root-recheck", 5000, 4096)
      if (check.exitCode == 0 && check.stdout.toString(Charsets.UTF_8).trim() == "0") {
        uid = 0
        detail = "Root 已就绪 · UID=0 · 执行自检通过"
        State.publish()
        return 0
      }
    }
    shellUid = -1

    detail = "正在等待 root 管理器授权…"
    State.publish()
    com.topjohnwu.superuser.Shell.enableVerboseLogging = false
    try {
      // Discard a cached ordinary shell after a previous denial, so Retry can really ask again.
      com.topjohnwu.superuser.Shell.getCachedShell()?.let {
        if (!it.isRoot || !it.isAlive) it.close()
      }
      val shell = com.topjohnwu.superuser.Shell.getShell()
      val actual = RootProbe.verify(shell.newJob(), shell.isRoot)
      shellUid = actual
      val future = CompletableFuture<Int>()
      pending = future
      detail = "已获得 UID=0，正在连接本地 Root 执行服务…"
      State.publish()
      handler.post {
        runCatching {
            com.topjohnwu.superuser.ipc.RootService.bind(
              Intent(context, RootExecutorService::class.java),
              connection,
            )
          }
          .onFailure { future.completeExceptionally(it) }
      }
      future.get(25, TimeUnit.SECONDS)
      val identity = execute(listOf("/system/bin/id", "-u"), "root-probe", 5000, 4096)
      if (identity.exitCode != 0 || identity.stdout.toString(Charsets.UTF_8).trim() != "0")
        throw BridgeError("PERMISSION_DENIED", "Root 服务命令执行 UID 校验失败")
      detail = "Root 已就绪 · UID=0 · 执行自检通过"
      State.publish()
      return uid
    } catch (e: Exception) {
      val cause = if (e is ExecutionException) e.cause ?: e else e
      val message =
        if (cause is TimeoutException) "Root 服务连接超时。请确认 root 管理器已允许本应用；可重试或切换 Shizuku。"
        else cause.message ?: "Root 授权失败"
      lost(if (shellUid == 0) "授权 UID=0，但执行服务未就绪：$message" else message)
      throw if (cause is BridgeError) cause else BridgeError("BACKEND_UNAVAILABLE", message)
    } finally {
      pending = null
    }
  }

  override fun execute(argv: List<String>, token: String, timeout: Int, limit: Int): ExecResult {
    val service = remote ?: throw BridgeError("BACKEND_UNAVAILABLE", detail)
    if (!service.asBinder().isBinderAlive) {
      lost("Root Binder 已退出")
      throw BridgeError("BACKEND_UNAVAILABLE", detail)
    }
    return try {
      readRemote(service, argv, token, timeout, limit)
    } catch (e: RemoteException) {
      lost("Root 服务退出，执行结果未知")
      throw BridgeError("BACKEND_UNAVAILABLE", detail)
    }
  }

  override fun status() = detail

  override fun cancel(token: String) {
    runCatching { remote?.cancel(token) }
  }

  fun disconnect() {
    handler.post {
      runCatching { com.topjohnwu.superuser.ipc.RootService.unbind(connection) }
      lost("Root 已断开；需要时重新授权连接")
    }
  }
}

class RootExecutorService : com.topjohnwu.superuser.ipc.RootService() {
  private val executor = PrivilegedService()

  override fun onBind(intent: Intent): IBinder = executor

  override fun onDestroy() {
    Processes.active.keys.toList().forEach { Processes.cancel(it) }
    super.onDestroy()
  }
}

private val ipcTimer = Executors.newScheduledThreadPool(1)

fun readRemote(
  service: IExecutor,
  argv: List<String>,
  token: String,
  timeout: Int,
  limit: Int,
): ExecResult {
  val fd = service.execute(token, argv.toTypedArray(), timeout.coerceIn(1, 30000), limit)
  val timer =
    ipcTimer.schedule(
      {
        runCatching { service.cancel(token) }
        runCatching { fd.close() }
      },
      timeout.toLong() + 3000,
      TimeUnit.MILLISECONDS,
    )
  return try {
    DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(fd)).use { ExecWire.read(it, limit) }
  } catch (e: IOException) {
    throw BridgeError("BACKEND_UNAVAILABLE", "本地执行通道中断，执行结果可能未知")
  } finally {
    timer.cancel(false)
    runCatching { fd.close() }
  }
}

class PrivilegedService : IExecutor.Stub() {
  override fun uid() = android.os.Process.myUid()

  override fun execute(
    token: String,
    argv: Array<out String>,
    timeoutMs: Int,
    limit: Int,
  ): ParcelFileDescriptor {
    val pipe = ParcelFileDescriptor.createPipe()
    Thread {
        DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use { out ->
          try {
            val r = Processes.run(argv.toList(), token, timeoutMs, limit)
            ExecWire.write(out, r)
          } catch (e: Exception) {
            runCatching {
              out.writeUTF((e as? BridgeError)?.code ?: "BACKEND_UNAVAILABLE")
              out.writeUTF(e.message ?: "UserService failed")
            }
          }
        }
      }
      .start()
    return pipe[0]
  }

  override fun cancel(token: String) = Processes.cancel(token)

  override fun destroy() {
    Processes.active.keys.toList().forEach { Processes.cancel(it) }
    android.os.Process.killProcess(android.os.Process.myPid())
  }
}

class ShizukuBackend(val c: Context) : ExecutionBackend {
  override val name = "shizuku"
  @Volatile var remote: IExecutor? = null
  val connected: Boolean
    get() = remote?.asBinder()?.isBinderAlive == true

  @Volatile var detail = "请启动 Shizuku 并授权连接"
  @Volatile var uid = -1
  private val args =
    Shizuku.UserServiceArgs(ComponentName(c, PrivilegedService::class.java))
      .daemon(false)
      .processNameSuffix("executor")
      .debuggable(false)
      .version(BuildConfig.VERSION_CODE)
  private val probeExecutor = Executors.newSingleThreadExecutor()
  private val epoch = java.util.concurrent.atomic.AtomicInteger()

  @Synchronized
  private fun lost(reason: String) {
    epoch.incrementAndGet()
    remote = null
    uid = -1
    detail = reason
    State.publish()
  }

  @Synchronized
  private fun lostIfCurrent(generation: Int, reason: String) {
    if (epoch.get() == generation) lost(reason)
  }

  @Synchronized
  private fun ready(generation: Int, service: IExecutor, actual: Int) {
    if (epoch.get() != generation || !service.asBinder().isBinderAlive) return
    if (
      !Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    ) {
      lost("Shizuku 授权或服务已失效")
      return
    }
    remote = service
    uid = actual
    detail = "Shizuku 已就绪 · UID=$actual · 执行自检通过"
    State.publish()
  }

  private val conn =
    object : ServiceConnection {
      override fun onServiceConnected(n: ComponentName, b: IBinder) {
        val generation = epoch.incrementAndGet()
        remote = null
        uid = -1
        detail = "Shizuku 已连接，正在执行自检…"
        State.publish()
        try {
          b.linkToDeath({ lostIfCurrent(generation, "Shizuku 服务已退出") }, 0)
          val service = IExecutor.Stub.asInterface(b)
          probeExecutor.execute {
            try {
              val actual = service.uid()
              val result =
                readRemote(
                  service,
                  listOf("/system/bin/id", "-u"),
                  "shizuku-probe-$generation",
                  5000,
                  4096,
                )
              BackendProbe.verify(actual, result)
              ready(generation, service, actual)
            } catch (e: Exception) {
              synchronized(this@ShizukuBackend) {
                if (epoch.get() == generation) lost("Shizuku 执行自检失败：${e.message}")
              }
            }
          }
        } catch (e: Exception) {
          lost("Shizuku 服务连接失败：${e.message}")
        }
      }

      override fun onServiceDisconnected(n: ComponentName) {
        lost("Shizuku 服务已断开")
      }
    }

  init {
    Shizuku.addRequestPermissionResultListener { code, grant ->
      if (code == 10) {
        if (grant == PackageManager.PERMISSION_GRANTED) bind()
        else {
          lost("Shizuku 授权被拒绝")
        }
      }
    }
    Shizuku.addBinderDeadListener { lost("Shizuku Binder 已失效") }
  }

  fun bind() {
    if (!Shizuku.pingBinder()) throw BridgeError("BACKEND_UNAVAILABLE", "请先安装并启动 Shizuku")
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      detail = "请在 Shizuku 弹窗中授权"
      State.publish()
      Shizuku.requestPermission(10)
      return
    }
    Shizuku.bindUserService(args, conn)
  }

  override fun status() = detail

  override fun execute(argv: List<String>, token: String, timeout: Int, limit: Int): ExecResult {
    if (!Shizuku.pingBinder()) {
      lost("Shizuku Binder 不可用")
      throw BridgeError("BACKEND_UNAVAILABLE", detail)
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      lost("Shizuku 授权已拒绝或撤销")
      throw BridgeError("PERMISSION_DENIED", detail)
    }
    val r = remote ?: throw BridgeError("BACKEND_UNAVAILABLE", "请授权并连接 Shizuku UserService")
    try {
      return readRemote(r, argv, token, timeout, limit)
    } catch (e: DeadObjectException) {
      lost("Shizuku 服务退出；执行结果可能未知")
      throw BridgeError("BACKEND_UNAVAILABLE", detail)
    }
  }

  override fun cancel(token: String) {
    runCatching { remote?.cancel(token) }
  }
}
