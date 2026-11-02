package starx.dev.agentbridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Page(val label: String, val icon: Int) {
  Home("首页", R.drawable.ic_home),
  Permissions("权限", R.drawable.ic_permission),
  Connection("连接", R.drawable.ic_connection),
  Security("安全", R.drawable.ic_security),
  Settings("设置", R.drawable.ic_settings),
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= 33)
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
    setContent {
      val vm: BridgeViewModel = viewModel()
      val theme by vm.uiTheme.collectAsStateWithLifecycle()
      BridgeTheme(theme) { BridgeAppUi(vm) }
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun BridgeAppUi(vm: BridgeViewModel = viewModel()) {
    var pageName by rememberSaveable { mutableStateOf(Page.Home.name) }
    val page = Page.entries.firstOrNull { it.name == pageName } ?: Page.Home
    val runtime by vm.runtime.collectAsStateWithLifecycle()
    val c by vm.connection.collectAsStateWithLifecycle()
    val security by vm.security.collectAsStateWithLifecycle()
    val rootBusy by vm.rootBusy.collectAsStateWithLifecycle()
    val deviceId by vm.deviceId.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val logs by vm.audit.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var about by remember { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val theme by vm.uiTheme.collectAsStateWithLifecycle()
    var advancedTls by rememberSaveable { mutableStateOf(false) }
    val portFocus = remember { FocusRequester() }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(page, c.loaded) {
      if (page == Page.Connection && c.loaded) {
        vm.prepareCertificate()
        vm.refreshAddresses()
      }
    }
    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      bottomBar = {
        if (!keyboardVisible) {
          if (LocalMiuix.current) {
            top.yukonga.miuix.kmp.basic.NavigationBar(
              items =
                Page.entries.map {
                  top.yukonga.miuix.kmp.basic.NavigationItem(
                    it.label,
                    androidx.compose.ui.graphics.vector.ImageVector.vectorResource(it.icon),
                  )
                },
              selected = page.ordinal,
              onClick = { pageName = Page.entries[it].name },
              showDivider = false,
            )
          } else {
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
              tonalElevation = 0.dp,
            ) {
              Page.entries.forEach { destination ->
                NavigationBarItem(
                  selected = page == destination,
                  onClick = { pageName = destination.name },
                  icon = { Icon(painterResource(destination.icon), null, Modifier.size(24.dp)) },
                  label = { Text(destination.label) },
                  alwaysShowLabel = true,
                  colors =
                    NavigationBarItemDefaults.colors(
                      selectedIconColor = MaterialTheme.colorScheme.primary,
                      selectedTextColor = MaterialTheme.colorScheme.primary,
                      indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                      unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                      unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
              }
            }
          }
        }
      },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
          Modifier.widthIn(max = 680.dp).fillMaxWidth().imePadding(),
          contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          item {
            val title: @Composable () -> Unit = {
              Text(
                page.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
              )
            }
            val status: @Composable () -> Unit = {
              StatusPill(
                if (runtime.running) "服务已启动"
                else if (runtime.phase == "starting") "正在启动" else "服务已停止",
                runtime.running,
              )
            }
            if (LocalDensity.current.fontScale > 1.4f) {
              Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                title()
                status()
              }
            } else {
              Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Box(Modifier.weight(1f)) { title() }
                status()
              }
            }
          }
          if (runtime.error.isNotEmpty()) item { Notice(runtime.error, true) }
          when (page) {
            Page.Home -> {
              item {
                HomeCard(tonal = true) {
                  Text(
                    "让 Agent 操作你的手机",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    "TAILSCALE · 直连",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    "在同一 Tailscale 网络中，安全地获取屏幕、点击与输入。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Spacer(Modifier.height(4.dp))
                  Action(
                    if (runtime.running) "停止控制服务"
                    else if (runtime.phase == "starting") "正在准备…" else "启动控制服务",
                    onClick = { if (runtime.running) vm.stop() else vm.start() },
                    enabled = runtime.phase !in setOf("starting", "stopping"),
                  )
                }
              }
              item {
                HomeCard {
                  SectionTitle("设备状态", "连接前先确认后端已授权")
                  Detail("执行后端", backendName(runtime.backend))
                  Detail("权限状态", permissionSummary(runtime.backend, runtime.backendStatus))
                  Detail("实际监听", runtime.listener, mono = true)
                }
              }
              item {
                HomeCard {
                  SectionTitle("开始连接", "三个步骤完成首次控制")
                  Step("1", "授权后端", "在权限页连接 Root 或 Shizuku。")
                  Step("2", "配置 Tailscale", "双方登录同一网络，在连接页保存地址与证书。")
                  Step("3", "复制控制令牌", "启动服务后，用安全页的令牌调用 SDK。")
                }
              }
            }
            Page.Permissions -> {
              item {
                CardBox {
                  SectionTitle("执行后端")
                  BridgeChoices(
                    options = listOf("root" to "Root", "shizuku" to "Shizuku"),
                    selected = runtime.backend,
                    onSelect = { if (runtime.backend != it) vm.setBackend(it) },
                  )
                  Text(
                    "切换后端会停止当前控制会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              if (runtime.backend == "root")
                item {
                  CardBox {
                    SectionTitle("Root 授权")
                    PermissionConnectionStatus(runtime)
                    if (rootBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Action(
                      if (rootBusy) "正在连接 Root…" else "申请/重试 Root 连接",
                      { vm.rootAuthorize() },
                      !rootBusy,
                    )
                  }
                }
              if (runtime.backend == "shizuku")
                item {
                  CardBox {
                    SectionTitle("Shizuku 授权")
                    PermissionConnectionStatus(runtime)
                    Action("申请/重试 Shizuku 连接", { vm.shizukuAuthorize() })
                  }
                }
              item {
                CardBox {
                  SectionTitle("中文与多行输入", "启用辅助输入法，操作完成后尝试恢复原输入法")
                  BridgeOutlinedButton(
                    { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                  ) {
                    Text("打开系统输入法设置")
                  }
                  SwitchLine("已启用 AgentBridge 输入法", "请先在上方系统设置中启用", security.ime) {
                    vm.setSecurity("imeEnabled", it)
                  }
                }
              }
            }
            Page.Connection -> {
              item {
                CardBox {
                  Text("被控端监听", style = MaterialTheme.typography.titleMedium)
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Input(
                      "监听 IP",
                      c.bind,
                      { vm.edit("bind", it) },
                      "100.91.0.15",
                      onNext = { portFocus.requestFocus() },
                      modifier = Modifier.weight(1f),
                    )
                    Input(
                      "端口",
                      c.port,
                      { vm.edit("port", it) },
                      "8443",
                      number = true,
                      focusRequester = portFocus,
                      modifier = Modifier.width(104.dp),
                    )
                  }
                  ConnectionActions(
                    onDetect = { vm.useDetectedTailscale() },
                    onWildcard = { vm.edit("bind", "0.0.0.0") },
                  )
                  val candidates = c.addresses.filter { ConnectionSettings.isTailscaleAddress(it) }
                  if (candidates.isNotEmpty()) {
                    Text(
                      "Tailscale 候选 IP",
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    SelectionContainer {
                      Text(
                        candidates.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                      )
                    }
                  }
                  Action(
                    if (c.busy) "正在保存…" else "保存连接设置",
                    { vm.saveConnection() },
                    c.loaded && !c.busy && !c.certificateBusy && runtime.phase == "stopped",
                  )
                }
              }
              item {
                CardBox {
                  SectionTitle("传输安全", "HTTPS 证书自动包含监听 IP 和检测到的 Tailscale IP")
                  if (!c.plain) {
                    if (c.certificateBusy) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                      ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在后台准备 TLS 证书…")
                      }
                    }
                    if (c.fingerprint.isNotEmpty()) {
                      Text("证书 SHA256", style = MaterialTheme.typography.labelLarge)
                      SelectionContainer {
                        Text(
                          compactFingerprint(c.fingerprint),
                          fontSize = 11.sp,
                          fontFamily = FontFamily.Monospace,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                    }
                    if (c.fingerprint.isNotEmpty()) {
                      BridgeTextButton(
                        { vm.copy("证书 SHA256", c.fingerprint) },
                        Modifier.align(Alignment.End),
                      ) {
                        Text("复制完整指纹")
                      }
                    }
                    BridgeOutlinedButton(
                      { vm.copyCertificate() },
                      Modifier.fillMaxWidth().heightIn(min = 48.dp),
                      enabled = c.certificate.isNotEmpty() && !c.certificateBusy && c.saved,
                    ) {
                      Text("复制 PEM 证书")
                    }
                    Text(
                      "复制证书保存为 bridge.pem，在控制端通过 --ca 使用。修改设置后先保存，再重新复制证书。",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  BridgeTextButton({ advancedTls = !advancedTls }, Modifier.align(Alignment.End)) {
                    Text(if (advancedTls) "收起高级设置" else "高级设置")
                  }
                  if (advancedTls) {
                    SwitchLine("可信网络 HTTP 模式", "仅在你信任整个访问网络时开启", c.plain) { vm.setPlain(it) }
                    if (!c.plain) {
                      Input("证书附加 IP / 域名（可选）", c.names, { vm.edit("names", it) }, "多个名称使用英文逗号分隔")
                    } else {
                      Text(
                        "HTTP 内容不由应用 TLS 加密；监听所有接口时可能被同一局域网访问。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                      )
                    }
                  }
                }
              }
            }
            Page.Security -> {
              item {
                CardBox {
                  Detail("设备 ID", deviceId.ifEmpty { "正在读取…" }, mono = true)
                  BridgeTextButton(
                    { vm.copy("Device ID", deviceId) },
                    Modifier.align(Alignment.End),
                    enabled = deviceId.isNotEmpty(),
                  ) {
                    Text("复制设备 ID")
                  }
                }
              }

              item {
                CardBox {
                  SectionTitle("控制令牌", "复制代表你在手机本地批准持有者访问")
                  Text(
                    "Tailscale 地址不能替代身份验证。请通过可信渠道把令牌交给你的 Agent。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  if (token.isNotEmpty())
                    SelectionContainer {
                      Text(token, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                  Action("批准并复制控制令牌", { vm.showToken(true) })
                  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BridgeTextButton({ if (token.isEmpty()) vm.showToken() else vm.hideToken() }) {
                      Text(if (token.isEmpty()) "显示令牌" else "隐藏令牌")
                    }
                    BridgeTextButton({ vm.revoke() }) { Text("撤销并轮换令牌") }
                  }
                }
              }
              item {
                CardBox {
                  SectionTitle("操作权限", "权限变更会停止现有控制会话")
                  SwitchLine("查看屏幕", "截图、界面树及状态查询", security.view) { vm.setSecurity("view", it) }
                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                  SwitchLine("界面操作", "点击、滑动、输入与启动应用", security.ui) { vm.setSecurity("ui", it) }
                  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                  SwitchLine(
                    "高级 shell",
                    "可执行任意命令；截图、点击无需开启",
                    security.shell,
                    enabled = security.loaded,
                  ) {
                    vm.setSecurity("shell", it)
                  }
                  if (security.shell) Detail("shell 后端", "跟随执行后端 · ${backendName(runtime.backend)}")
                }
              }
              item {
                CardBox {
                  SwitchLine("开机自动启动", "系统或厂商可能限制后台启动", security.boot) {
                    vm.setSecurity("boot", it)
                  }
                  BridgeButton(
                    { vm.emergency() },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors =
                      ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(16.dp),
                  ) {
                    Text("紧急停止并撤销令牌")
                  }
                }
              }
            }
            Page.Settings -> {
              item {
                CardBox {
                  SectionTitle("界面主题")
                  BridgeChoices(
                    options = listOf("miuix" to "miuix", "material3" to "Material 3"),
                    selected = theme,
                    onSelect = { vm.setUiTheme(it) },
                  )
                }
              }
              item {
                CardBox {
                  BridgeOutlinedButton({ showLogs = !showLogs }, Modifier.fillMaxWidth()) {
                    Text(if (showLogs) "收起运行日志" else "运行日志")
                  }
                  BridgeOutlinedButton({ about = true }, Modifier.fillMaxWidth()) {
                    Text("关于 AgentBridge")
                  }
                }
              }
              if (showLogs)
                item {
                  CardBox {
                    SectionTitle("运行日志", "仅记录状态与必要审计，不记录令牌和输入内容")
                    val scroll = rememberScrollState()
                    Surface(
                      Modifier.fillMaxWidth().height(340.dp),
                      shape = RoundedCornerShape(12.dp),
                      color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                      SelectionContainer {
                        Text(
                          if (logs.isEmpty()) "暂无日志" else logs.joinToString("\n"),
                          Modifier.padding(14.dp).verticalScroll(scroll),
                          fontSize = 12.sp,
                          lineHeight = 19.sp,
                          fontFamily = FontFamily.Monospace,
                        )
                      }
                    }
                    Row(
                      Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                      BridgeOutlinedButton(
                        { vm.copy("AgentBridge audit", logs.joinToString("\n")) },
                        Modifier.weight(1f).heightIn(min = 48.dp),
                      ) {
                        Text("复制日志")
                      }
                      BridgeOutlinedButton(
                        {
                          startActivity(
                            Intent.createChooser(
                              Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, logs.joinToString("\n")),
                              "导出运行日志",
                            )
                          )
                        },
                        Modifier.weight(1f).heightIn(min = 48.dp),
                      ) {
                        Text("导出日志")
                      }
                    }
                  }
                }
            }
          }
          item { Spacer(Modifier.height(8.dp)) }
        }
      }
    }
    if (message.isNotEmpty())
      BridgeAlertDialog(
        onDismissRequest = { vm.dismissMessage() },
        title = { Text("操作结果") },
        text = { SelectionContainer { Text(message) } },
        confirmButton = { BridgeTextButton({ vm.dismissMessage() }) { Text("知道了") } },
        dismissButton = {
          BridgeTextButton({ vm.copy("AgentBridge result", message) }) { Text("复制结果") }
        },
      )
    if (about)
      BridgeAlertDialog(
        onDismissRequest = { about = false },
        title = { Text("AgentBridge ${BuildConfig.VERSION_NAME}") },
        text = {
          Text(
            "Tailscale 直连 · Root / Shizuku\nApache-2.0\n\n不绕过锁屏、FLAG_SECURE、DRM 或安全键盘。实际行为仍受系统与 ROM 权限限制。"
          )
        },
        confirmButton = { BridgeTextButton({ about = false }) { Text("完成") } },
      )
  }
}

@Composable
private fun PermissionConnectionStatus(runtime: RuntimeSnapshot) {
  val status = permissionConnection(runtime)
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      status.headline,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (status.connected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
    )
    if (status.detail.isNotEmpty())
      Text(
        status.detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
}

@Composable
private fun HomeCard(tonal: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
  if (LocalMiuix.current) ClassicMaterialHome { CardBox(tonal, content) }
  else CardBox(tonal, content)
}

@Composable
private fun CardBox(tonal: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
  if (LocalMiuix.current) {
    top.yukonga.miuix.kmp.basic.Card(
      modifier = Modifier.fillMaxWidth(),
      cornerRadius = 24.dp,
      insideMargin = PaddingValues(20.dp),
      colors =
        top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
          color =
            if (tonal) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
    ) {
      Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
      )
    }
    return
  }
  val classic = LocalClassicHome.current
  val cardShape = if (classic) RoundedCornerShape(20.dp) else MaterialTheme.shapes.large
  val colors =
    CardDefaults.cardColors(
      containerColor =
        if (tonal) MaterialTheme.colorScheme.primaryContainer
        else if (classic) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerLow,
      contentColor =
        if (tonal && !classic) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
    )
  val body: @Composable ColumnScope.() -> Unit = {
    Column(
      Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(if (classic) 14.dp else 16.dp),
      content = content,
    )
  }
  if (classic || tonal)
    Card(Modifier.fillMaxWidth(), shape = cardShape, colors = colors, content = body)
  else
    OutlinedCard(
      Modifier.fillMaxWidth(),
      shape = cardShape,
      colors = colors,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      content = body,
    )
}

@Composable
private fun SectionTitle(title: String, subtitle: String = "") {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (subtitle.isNotEmpty())
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
  Surface(
    shape = RoundedCornerShape(50),
    color =
      if (active) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
      text,
      Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
      style = MaterialTheme.typography.labelMedium,
      color =
        if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Detail(label: String, value: String, mono: Boolean = false) {
  val labelContent: @Composable () -> Unit = {
    Text(
      label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  val valueContent: @Composable () -> Unit = {
    SelectionContainer {
      Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
      )
    }
  }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 240.dp || LocalDensity.current.fontScale > 1.3f) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labelContent()
        valueContent()
      }
    } else {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Box(Modifier.width(if (LocalClassicHome.current) 72.dp else 80.dp)) { labelContent() }
        Box(Modifier.weight(1f)) { valueContent() }
      }
    }
  }
}

@Composable
private fun Step(number: String, title: String, subtitle: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
    Surface(
      Modifier.size(30.dp),
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.primaryContainer,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(number, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
      }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun Action(text: String, onClick: () -> Unit, enabled: Boolean = true) {
  BridgeButton(
    onClick,
    Modifier.fillMaxWidth().heightIn(min = 52.dp),
    enabled = enabled,
    shape = RoundedCornerShape(if (LocalClassicHome.current) 14.dp else 16.dp),
  ) {
    Text(text, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun Input(
  label: String,
  value: String,
  onChange: (String) -> Unit,
  placeholder: String,
  number: Boolean = false,
  focusRequester: FocusRequester? = null,
  onNext: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val focus = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  if (LocalMiuix.current) {
    top.yukonga.miuix.kmp.basic.TextField(
      value = value,
      onValueChange = onChange,
      modifier =
        modifier
          .fillMaxWidth()
          .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
      label = label,
      singleLine = true,
      keyboardOptions =
        KeyboardOptions(
          keyboardType = if (number) KeyboardType.Number else KeyboardType.Uri,
          capitalization = KeyboardCapitalization.None,
          autoCorrectEnabled = false,
          imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done,
        ),
      keyboardActions =
        KeyboardActions(
          onNext = { onNext?.invoke() },
          onDone = {
            focus.clearFocus()
            keyboard?.hide()
          },
        ),
    )
    return
  }
  OutlinedTextField(
    value,
    onChange,
    modifier
      .fillMaxWidth()
      .heightIn(min = 64.dp)
      .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    label = { Text(label) },
    placeholder = { Text(placeholder) },
    singleLine = true,
    shape = MaterialTheme.shapes.medium,
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        cursorColor = MaterialTheme.colorScheme.primary,
      ),
    keyboardOptions =
      KeyboardOptions(
        keyboardType = if (number) KeyboardType.Number else KeyboardType.Uri,
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        imeAction = if (onNext != null) ImeAction.Next else ImeAction.Done,
      ),
    keyboardActions =
      KeyboardActions(
        onNext = { onNext?.invoke() },
        onDone = {
          focus.clearFocus()
          keyboard?.hide()
        },
      ),
  )
}

@Composable
private fun SwitchLine(
  title: String,
  subtitle: String,
  value: Boolean,
  enabled: Boolean = true,
  onChange: (Boolean) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (LocalMiuix.current) top.yukonga.miuix.kmp.basic.Switch(value, onChange, enabled = enabled)
    else Switch(value, onChange, enabled = enabled)
  }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
  Surface(
    Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color =
      if (error) MaterialTheme.colorScheme.errorContainer
      else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
      text,
      Modifier.padding(16.dp),
      style = MaterialTheme.typography.bodySmall,
      color =
        if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Respect system font scale. Stack only when equal-width actions cannot contain both labels. */
@Composable
private fun ConnectionActions(onDetect: () -> Unit, onWildcard: () -> Unit) {
  val style =
    MaterialTheme.typography.labelLarge.copy(
      fontSize = 13.sp,
      lineHeight = 18.sp,
      letterSpacing = 0.sp,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center,
    )
  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val longestPx =
    maxOf(
      measurer.measure(AnnotatedString("自动选择候选 IP"), style = style).size.width,
      measurer.measure(AnnotatedString("监听所有接口"), style = style).size.width,
    )
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stacked = maxWidth < with(density) { longestPx.toDp() } * 2 + 24.dp
    val detect: @Composable (Modifier) -> Unit = { modifier ->
      BridgeTextButton(
        onDetect,
        modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
      ) {
        Text("自动选择候选 IP", style = style, softWrap = stacked)
      }
    }
    if (stacked) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        detect(Modifier.fillMaxWidth())
        WildcardButton(Modifier.fillMaxWidth(), style, true, onWildcard)
      }
    } else {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        detect(Modifier.weight(1f))
        WildcardButton(Modifier.weight(1f), style, false, onWildcard)
      }
    }
  }
}

@Composable
private fun WildcardButton(
  modifier: Modifier,
  style: TextStyle,
  wrap: Boolean,
  confirm: () -> Unit,
) {
  var ask by remember { mutableStateOf(false) }
  BridgeTextButton(
    { ask = true },
    modifier.heightIn(min = 48.dp),
    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
  ) {
    Text("监听所有接口", style = style, softWrap = wrap)
  }
  if (ask)
    BridgeAlertDialog(
      onDismissRequest = { ask = false },
      title = { Text("确认监听所有接口？") },
      text = { Text("这会绑定 0.0.0.0，可能同时向局域网开放。仍需控制令牌；请优先使用 HTTPS，并限制 Tailscale ACL。") },
      confirmButton = {
        BridgeTextButton({
          confirm()
          ask = false
        }) {
          Text("我了解，使用所有接口")
        }
      },
      dismissButton = { BridgeTextButton({ ask = false }) { Text("取消") } },
    )
}

private fun backendName(name: String) =
  when (name) {
    "root" -> "Root"
    "shizuku" -> "Shizuku"
    else -> "未选择"
  }
