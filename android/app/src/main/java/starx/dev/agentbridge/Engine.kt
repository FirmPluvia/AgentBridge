package starx.dev.agentbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.Build
import java.util.*
import java.util.concurrent.*
import javax.xml.parsers.DocumentBuilderFactory
import org.json.*

class Job(
  val id: String,
  val owner: String,
  val session: String,
  val backend: ExecutionBackend,
  val requestId: String,
) {
  @Volatile var state = "queued"
  @Volatile var result: JSONObject? = null
  @Volatile var future: Future<*>? = null
}

data class Screen(
  val id: String,
  val width: Int,
  val height: Int,
  val rotation: Int,
  val at: Long,
  val inputWidth: Int,
  val inputHeight: Int,
)

class Engine(val context: Context) {
  val root = RootBackend(context)
  val shizuku = ShizukuBackend(context)
  private val unavailable = UnavailableBackend()
  var selected: ExecutionBackend
    get() =
      when (State.pref("backend", "root")) {
        "shizuku" -> shizuku
        "root" -> root
        else -> unavailable
      }
    set(v) {
      State.put("backend", v.name)
    }

  private val ui = Executors.newSingleThreadExecutor()
  private val reads = Executors.newFixedThreadPool(3)
  val jobs = ConcurrentHashMap<String, Job>()
  private val cache = ConcurrentHashMap<String, JSONObject>()
  private val journal: SQLiteDatabase =
    context.openOrCreateDatabase("requests.db", 0, null).apply {
      execSQL(
        "CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, owner TEXT NOT NULL, at INTEGER NOT NULL)"
      )
    }
  private val ledger =
    RequestLedger(
      { key ->
        journal.rawQuery("SELECT owner FROM requests WHERE id=?", arrayOf(key)).use {
          it.moveToFirst()
        }
      },
      { key ->
        journal.execSQL(
          "INSERT INTO requests(id,owner,at) VALUES(?,?,?)",
          arrayOf<Any>(key, key.substringBefore(':'), System.currentTimeMillis()),
        )
      },
    )
  @Volatile var screen: Screen? = null
  @Volatile
  var session: String? = null
    set(value) {
      field = value
      State.publish()
    }

  @Volatile var sessionOwner: String? = null
  private val stopped = ConcurrentHashMap.newKeySet<String>()
  private val mutations = setOf("tap", "long-press", "swipe", "text", "key", "launch", "shell")
  private val rate = ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()

  fun result(
    id: String,
    b: String,
    success: Boolean,
    data: Any? = null,
    error: BridgeError? = null,
    duration: Long = 0,
    state: String = "completed",
  ) =
    JSONObject()
      .put("requestId", id)
      .put("deviceId", State.deviceId)
      .put("backend", b)
      .put("success", success)
      .put("durationMs", duration)
      .put("state", state)
      .put("data", data ?: JSONObject.NULL)
      .put(
        "error",
        if (error == null) JSONObject.NULL
        else
          JSONObject()
            .put("code", error.code)
            .put("message", error.message)
            .put("retryable", error.retryable),
      )

  @Synchronized
  fun stopAll() {
    stopped.addAll(jobs.values.map { "${it.owner}:${it.session}" })
    jobs.values.filter { it.state in setOf("queued", "running") }.forEach { cancel(it) }
    session = null
    sessionOwner = null
    screen = null
  }

  private fun cancel(j: Job) {
    val running = j.state == "running"
    j.state = if (running) "unknown" else "cancelled"
    j.backend.cancel(j.id)
    j.future?.cancel(true)
    j.result =
      result(
        j.requestId,
        j.backend.name,
        false,
        error = BridgeError("CANCELLED", if (running) "已请求终止，已经发生的操作无法撤销" else "排队操作已取消"),
        state = j.state,
      )
  }

  fun authenticate(token: String): Boolean =
    java.security.MessageDigest.isEqual(token.toByteArray(), State.token().toByteArray())

  fun handle(
    path: String,
    body: JSONObject,
    owner: String = "local",
    scopes: Set<String> = setOf("view", "ui"),
  ): JSONObject {
    val id = body.optString("requestId").ifEmpty { UUID.randomUUID().toString() }
    val started = System.currentTimeMillis()
    val backend = selected
    try {
      if (!State.running) throw BridgeError("CANCELLED", "控制服务已停止")
      val q = rate.getOrPut(owner) { java.util.ArrayDeque() }
      synchronized(q) {
        while (q.isNotEmpty() && q.first < started - 60000) q.removeFirst()
        if (q.size >= 120) throw BridgeError("RATE_LIMITED", "每分钟最多 120 个请求", true)
        q.addLast(started)
      }
      if (body.optString("deviceId", State.deviceId) != State.deviceId)
        throw BridgeError("UNAUTHORIZED", "设备不匹配")
      val deadline = body.optLong("deadline", started + 15000)
      if (deadline <= started) throw BridgeError("TIMEOUT", "请求到达前已过期；未执行")
      if (deadline > started + 120000) throw BridgeError("INVALID_ARGUMENT", "deadline 最长 120 秒")
      val op = path.removePrefix("/api/v1/").trim('/')
      if (id.length > 128) throw BridgeError("INVALID_ARGUMENT", "requestId 最长 128 字符")
      val mutation = op in mutations
      val scope =
        if (op == "shell") "shell"
        else if (mutation || op.startsWith("sessions/") || op.endsWith("/cancel")) "ui" else "view"
      if (scope !in scopes) throw BridgeError("PERMISSION_DENIED", "控制端没有 $scope 权限")
      if (
        op == "shell" && !ShellAccess.permits(State.pref("shell", "false") == "true", backend.name)
      )
        throw BridgeError("PERMISSION_DENIED", "任意 shell 默认关闭；需本地授权并连接当前执行后端")
      if (op.startsWith("requests/")) {
        val queried = op.substringAfter("requests/")
        return cache["$owner:$queried"]
          ?: result(
            queried,
            backend.name,
            false,
            error = BridgeError("UNKNOWN_RESULT", "未保留结果，请勿重新执行副作用请求"),
            state = "unknown",
          )
      }
      if (op.startsWith("jobs/")) {
        val j = jobs[op.split('/')[1]] ?: throw BridgeError("UNSUPPORTED", "任务不存在")
        if (j.owner != owner) throw BridgeError("UNAUTHORIZED", "任务属于其他控制端")
        if (op.endsWith("/cancel")) cancel(j)
        return result(
          id,
          j.backend.name,
          true,
          JSONObject()
            .put("jobId", j.id)
            .put("state", j.state)
            .put("result", j.result ?: JSONObject.NULL),
        )
      }
      val sid = body.optString("sessionId")
      if (op.startsWith("sessions/") && op.endsWith("/stop")) {
        val target = op.split('/')[1]
        synchronized(this) {
          if (sessionOwner != null && sessionOwner != owner)
            throw BridgeError("UNAUTHORIZED", "会话属于其他控制端")
          stopped.add("$owner:$target")
          jobs.values.filter { it.session == target && it.owner == owner }.forEach { cancel(it) }
          if (session == target) {
            session = null
            sessionOwner = null
          }
        }
        return result(id, backend.name, true, JSONObject().put("stopped", true).put("undo", false))
      }
      val key = "$owner:$id"
      synchronized(this) {
        cache[key]?.let {
          return it
        }
        if (mutation) {
          if (sid.isEmpty()) throw BridgeError("INVALID_ARGUMENT", "界面操作必须提供 sessionId")
          if (stopped.contains("$owner:$sid")) throw BridgeError("CANCELLED", "会话已停止")
          if (session != null && (session != sid || sessionOwner != owner))
            throw BridgeError("SESSION_BUSY", "设备被另一个控制会话占用")
          session = sid
          sessionOwner = owner
        }
        if (!ledger.claim(owner, id))
          return result(
            id,
            backend.name,
            false,
            error = BridgeError("UNKNOWN_RESULT", "请求曾被接收；结果已不可用，禁止自动重试"),
            state = "unknown",
          )
        if (jobs.size >= 128) {
          jobs.values
            .filter { it.state !in setOf("queued", "running") }
            .take(32)
            .forEach {
              jobs.remove(it.id)
              cache.remove("${it.owner}:${it.requestId}")
            }
        }
        if (jobs.size >= 256) throw BridgeError("OUTPUT_LIMIT_EXCEEDED", "待处理任务过多")
        val job = Job(UUID.randomUUID().toString(), owner, sid, backend, id)
        jobs[job.id] = job
        val accepted =
          result(id, backend.name, true, JSONObject().put("jobId", job.id), state = "queued")
        cache[key] = accepted
        job.future =
          (if (mutation) ui else reads).submit {
            if (job.state != "queued") return@submit
            job.state = "running"
            val answer =
              try {
                if (System.currentTimeMillis() >= deadline) throw BridgeError("TIMEOUT", "排队超时；未执行")
                if (stopped.contains("$owner:$sid")) throw BridgeError("CANCELLED", "会话已停止")
                val value =
                  execute(
                    op,
                    body,
                    backend,
                    job.id,
                    (deadline - System.currentTimeMillis()).toInt(),
                  )
                result(
                  id,
                  backend.name,
                  true,
                  value,
                  duration = System.currentTimeMillis() - started,
                )
              } catch (e: Exception) {
                val er =
                  if (e is BridgeError) e
                  else BridgeError("BACKEND_UNAVAILABLE", e.message ?: "执行失败")
                result(
                  id,
                  backend.name,
                  false,
                  data =
                    if (op == "shell")
                      JSONObject()
                        .put("stdout", "")
                        .put("stderr", "")
                        .put("exitCode", JSONObject.NULL)
                        .put("executionState", "unknown")
                        .put("outputComplete", false)
                    else null,
                  error = er,
                  duration = System.currentTimeMillis() - started,
                  state =
                    if (mutation && er.code in setOf("TIMEOUT", "BACKEND_UNAVAILABLE", "CANCELLED"))
                      "unknown"
                    else "failed",
                )
              }
            if (job.state == "running") {
              job.result = answer
              job.state = answer.getString("state")
              cache[key] = answer
            }
            State.log(
              "controller=$owner tool=$op backend=${backend.name} duration=${System.currentTimeMillis()-started} result=${job.state}"
            )
          }
        if (body.optBoolean("async", op == "shell")) return accepted
      }
      val job = jobs.values.first { it.requestId == id && it.owner == owner }
      try {
        job.future?.get((deadline - started).coerceAtLeast(1), TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        return result(
          id,
          backend.name,
          false,
          JSONObject().put("jobId", job.id),
          BridgeError("TIMEOUT", "等待结果超时，请查询任务；不得自动重复副作用请求"),
          System.currentTimeMillis() - started,
          "unknown",
        )
      }
      return job.result ?: cache[key]!!
    } catch (e: Exception) {
      return result(
        id,
        backend.name,
        false,
        error = if (e is BridgeError) e else BridgeError("INVALID_ARGUMENT", e.message ?: "无效请求"),
        duration = System.currentTimeMillis() - started,
      )
    }
  }

  private fun displayInfo(): JSONObject {
    val display =
      context
        .getSystemService(android.hardware.display.DisplayManager::class.java)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY)
    val metrics = android.util.DisplayMetrics()
    display.getRealMetrics(metrics)
    return JSONObject()
      .put("width", metrics.widthPixels)
      .put("height", metrics.heightPixels)
      .put("rotation", display.rotation)
  }

  private fun execute(
    op: String,
    p: JSONObject,
    b: ExecutionBackend,
    token: String,
    timeout: Int,
  ): Any {
    val operationDeadline = System.currentTimeMillis() + timeout
    fun remaining(): Int {
      val left = operationDeadline - System.currentTimeMillis()
      if (left <= 0) throw BridgeError("TIMEOUT", "操作到达截止时间，副作用可能已经发生")
      return left.coerceAtMost(30000).toInt()
    }
    fun run(vararg a: String, limit: Int = 1048576): ExecResult {
      if (!State.running || Thread.currentThread().isInterrupted || jobs[token]?.state != "running")
        throw BridgeError("CANCELLED", "控制已停止")
      val r = b.execute(a.toList(), token, remaining(), limit)
      if (r.exitCode != 0)
        throw BridgeError(
          if (r.exitCode == 124 || r.exitCode == 137) "TIMEOUT" else "PERMISSION_DENIED",
          "命令退出 ${r.exitCode}: ${r.stderr.toString(Charsets.UTF_8).take(300)}",
        )
      return r
    }
    fun text(vararg a: String) = run(*a).stdout.toString(Charsets.UTF_8)
    fun rotation(): Int {
      val dump = text("dumpsys", "input")
      return Regex("SurfaceOrientation: (\\d)").find(dump)?.groupValues?.get(1)?.toInt()
        ?: throw BridgeError("UNSUPPORTED", "ROM 不提供显示旋转信息")
    }
    fun logicalSize(rot: Int): Pair<Int, Int> {
      val value = text("wm", "size")
      val m =
        Regex("(\\d+)x(\\d+)").findAll(value).lastOrNull()
          ?: throw BridgeError("UNSUPPORTED", "无法读取输入坐标尺寸")
      val w = m.groupValues[1].toInt()
      val h = m.groupValues[2].toInt()
      return if (rot % 2 == 1) h to w else w to h
    }
    fun point(x: String, y: String): Pair<Int, Int> {
      val s = screen ?: throw BridgeError("STALE_SCREEN", "请先截图")
      if (
        p.optString("imageId") != s.id ||
          System.currentTimeMillis() - s.at > 30000 ||
          rotation() != s.rotation
      )
        throw BridgeError("STALE_SCREEN", "截图已过期或屏幕已旋转，请重新截图")
      val xx = p.getInt(x)
      val yy = p.getInt(y)
      if (xx !in 0 until s.width || yy !in 0 until s.height)
        throw BridgeError("INVALID_ARGUMENT", "坐标超出截图实际像素范围")
      val logical = logicalSize(s.rotation)
      if (logical.first != s.inputWidth || logical.second != s.inputHeight)
        throw BridgeError("STALE_SCREEN", "显示缩放或逻辑尺寸已变化")
      return (xx.toLong() * s.inputWidth / s.width).toInt().coerceIn(0, s.inputWidth - 1) to
        (yy.toLong() * s.inputHeight / s.height).toInt().coerceIn(0, s.inputHeight - 1)
    }
    return when (op) {
      "status" ->
        JSONObject()
          .put("display", displayInfo())
          .put("deviceId", State.deviceId)
          .put("model", Build.MODEL)
          .put("android", Build.VERSION.RELEASE)
          .put("sdk", Build.VERSION.SDK_INT)
          .put("backend", b.name)
          .put("backendStatus", b.status())
          .put("sessionId", session ?: JSONObject.NULL)
          .put("listener", State.listener)
          .put("connectionMode", "tailscale-direct")
          .put(
            "screen",
            screen?.let {
              JSONObject()
                .put("width", it.width)
                .put("height", it.height)
                .put("rotation", it.rotation)
            } ?: JSONObject.NULL,
          )
      "capabilities" ->
        JSONObject()
          .put(
            "available",
            when (b) {
              is RootBackend -> b.uid == 0
              is ShizukuBackend -> b.remote != null
              else -> false
            },
          )
          .put("backend", b.name)
          .put("backendStatus", b.status())
          .put(
            "operations",
            JSONArray(
              listOf(
                "screenshot",
                "tap",
                "long-press",
                "swipe",
                "key",
                "text",
                "apps",
                "launch",
                "foreground",
                "ui-tree",
                "locate",
              )
            ),
          )
          .put("conditional", true)
          .put("note", "能力依赖已授权后端、ROM 和当前页面；操作失败返回明确错误")
          .put("textRequiresIme", true)
          .put("shell", ShellAccess.permits(State.pref("shell") == "true", b.name))
          .put("secureScreens", false)
          .put("coordinateMaxAgeMs", 30000)
      "screenshot" -> {
        jobs.values
          .filter { it.result?.optJSONObject("data")?.has("imageBase64") == true }
          .forEach { j ->
            val expired =
              result(
                j.requestId,
                j.backend.name,
                false,
                error = BridgeError("STALE_SCREEN", "截图二进制已从内存淘汰，请重新截图"),
              )
            j.result = expired
            cache["${j.owner}:${j.requestId}"] = expired
          }
        val rot = rotation()
        val bytes = run("screencap", "-p", limit = 16 * 1024 * 1024).stdout
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (
          options.outWidth <= 0 ||
            !bytes
              .take(8)
              .toByteArray()
              .contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        )
          throw BridgeError("PERMISSION_DENIED", "未获得有效 PNG；可能为权限或受保护页面")
        if (rotation() != rot) throw BridgeError("STALE_SCREEN", "截图期间发生旋转")
        val logical = logicalSize(rot)
        val s =
          Screen(
            UUID.randomUUID().toString(),
            options.outWidth,
            options.outHeight,
            rot,
            System.currentTimeMillis(),
            logical.first,
            logical.second,
          )
        screen = s
        JSONObject()
          .put("imageId", s.id)
          .put("width", s.width)
          .put("height", s.height)
          .put("rotation", s.rotation)
          .put("timestamp", s.at)
          .put("mimeType", "image/png")
          .put(
            "imageBase64",
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
          )
      }
      "tap",
      "long-press" -> {
        val (x, y) = point("x", "y")
        if (op == "tap") run("input", "tap", "$x", "$y")
        else
          run(
            "input",
            "swipe",
            "$x",
            "$y",
            "$x",
            "$y",
            p.optInt("durationMs", 700).coerceIn(300, 5000).toString(),
          )
        JSONObject().put("executed", true)
      }
      "swipe" -> {
        val a = point("x1", "y1")
        val z = point("x2", "y2")
        run(
          "input",
          "swipe",
          "${a.first}",
          "${a.second}",
          "${z.first}",
          "${z.second}",
          p.optInt("durationMs", 300).coerceIn(50, 5000).toString(),
        )
        JSONObject().put("executed", true)
      }
      "key" -> {
        val keys =
          mapOf(
            "back" to "4",
            "home" to "3",
            "recents" to "187",
            "enter" to "66",
            "volume-up" to "24",
            "volume-down" to "25",
          )
        run(
          "input",
          "keyevent",
          keys[p.getString("key")] ?: throw BridgeError("INVALID_ARGUMENT", "不支持的按键"),
        )
        JSONObject().put("executed", true)
      }
      "text" -> {
        if (State.pref("imeEnabled") != "true")
          throw BridgeError("PERMISSION_DENIED", "需在手机权限页面启用辅助输入法")
        val old = text("settings", "get", "secure", "default_input_method").trim()
        val ours = "${context.packageName}/.BridgeIme"
        try {
          run("ime", "set", ours)
          val until = System.currentTimeMillis() + 2000
          while (BridgeIme.instance == null && System.currentTimeMillis() < until) Thread.sleep(50)
          val ime = BridgeIme.instance ?: throw BridgeError("BACKEND_UNAVAILABLE", "输入法未连接")
          ime.commit(p.getString("text"))
        } finally {
          if (old.isNotEmpty() && old != "null" && old != ours)
            runCatching { run("ime", "set", old) }
        }
        JSONObject().put("committed", true)
      }
      "apps" -> {
        val intent =
          android.content
            .Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        JSONArray(
          context.packageManager
            .queryIntentActivities(intent, 0)
            .map {
              JSONObject()
                .put("packageName", it.activityInfo.packageName)
                .put("label", it.loadLabel(context.packageManager).toString())
            }
            .distinctBy { it.getString("packageName") }
        )
      }
      "launch" -> {
        val pkg = p.getString("packageName")
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(pkg))
          throw BridgeError("INVALID_ARGUMENT", "包名格式无效")
        val intent =
          context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw BridgeError("UNSUPPORTED", "未找到可启动应用")
        val cmp =
          intent.component?.flattenToString() ?: throw BridgeError("UNSUPPORTED", "无启动 Activity")
        text("am", "start", "-n", cmp)
        JSONObject().put("launched", pkg)
      }
      "foreground" -> {
        val dump = text("dumpsys", "activity", "activities")
        val m =
          Regex("(?:mResumedActivity|topResumedActivity).*? ([a-zA-Z0-9_.]+)/").find(dump)
            ?: throw BridgeError("UNSUPPORTED", "ROM 未提供前台 Activity")
        JSONObject().put("packageName", m.groupValues[1])
      }
      "ui-tree",
      "locate" -> {
        val path = "/data/local/tmp/agentbridge-$token.xml"
        val xml =
          try {
            run("uiautomator", "dump", "--compressed", path)
            text("cat", path)
          } finally {
            runCatching { run("rm", "-f", path) }
          }
        if (!xml.contains("<hierarchy")) throw BridgeError("UNSUPPORTED", "当前页面无法获取 UI hierarchy")
        if (op == "ui-tree") JSONObject().put("xml", xml)
        else {
          val f = DocumentBuilderFactory.newInstance()
          f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
          val nodes =
            f.newDocumentBuilder().parse(xml.byteInputStream()).getElementsByTagName("node")
          val matches = JSONArray()
          for (i in 0 until nodes.length) {
            val n = nodes.item(i) as org.w3c.dom.Element
            val attrs =
              mapOf(
                "resourceId" to "resource-id",
                "text" to "text",
                "description" to "content-desc",
              )
            if (
              attrs.any { p.has(it.key) } &&
                attrs
                  .filter { p.has(it.key) }
                  .all { n.getAttribute(it.value) == p.getString(it.key) }
            )
              matches.put(
                JSONObject()
                  .put("resourceId", n.getAttribute("resource-id"))
                  .put("text", n.getAttribute("text"))
                  .put("description", n.getAttribute("content-desc"))
                  .put("bounds", n.getAttribute("bounds"))
              )
          }
          JSONObject().put("matches", matches)
        }
      }
      "shell" -> {
        val a = p.getJSONArray("argv")
        if (a.length() == 0 || a.length() > 128)
          throw BridgeError("INVALID_ARGUMENT", "argv 需为 1–128 个参数")
        val r =
          b.execute(
            (0 until a.length()).map { a.getString(it) },
            token,
            remaining(),
            p.optInt("outputLimit", 1048576).coerceIn(1024, 1048576),
          )
        if (r.exitCode == 124 || r.exitCode == 137) throw BridgeError("TIMEOUT", "后端命令达到超时限制，结果未知")
        JSONObject()
          .put("stdout", r.stdout.toString(Charsets.UTF_8))
          .put("stderr", r.stderr.toString(Charsets.UTF_8))
          .put("exitCode", r.exitCode)
          .put("executionState", r.state)
      }
      else -> throw BridgeError("UNSUPPORTED", "未知 API: $op")
    }
  }
}
