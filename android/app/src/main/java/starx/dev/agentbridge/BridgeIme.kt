package starx.dev.agentbridge

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.*

/**
 * Local input surface and remote Unicode commit path. No keystroke logging or input persistence.
 */
class BridgeIme : InputMethodService() {
  companion object {
    @Volatile var instance: BridgeIme? = null
  }

  private val main = Handler(Looper.getMainLooper())
  private var body: LinearLayout? = null
  private var status: TextView? = null
  private var symbols = false
  private var shifted = false
  private var capsLock = false
  private var editorStarted = false
  private var dark = false
  private var panel = 0
  private var keyColor = 0
  private var ink = 0
  private var muted = 0
  private var accent = 0
  private var accentText = 0
  private val repeatDelete =
    object : Runnable {
      override fun run() {
        deleteOne()
        main.postDelayed(this, 75)
      }
    }

  override fun onCreate() {
    super.onCreate()
    instance = this
  }

  override fun onDestroy() {
    editorStarted = false
    body = null
    status = null
    instance = null
    main.removeCallbacks(repeatDelete)
    super.onDestroy()
  }

  override fun onEvaluateFullscreenMode() = false

  override fun onStartInput(info: EditorInfo, restarting: Boolean) {
    super.onStartInput(info, restarting)
    main.removeCallbacks(repeatDelete)
    editorStarted = true
    shifted = false
    capsLock = false
    symbols = false
    updateStatus("远程输入已就绪 · 中文可切换系统输入法")
    renderKeys()
  }

  override fun onFinishInput() {
    editorStarted = false
    main.removeCallbacks(repeatDelete)
    updateStatus("等待可编辑控件")
    super.onFinishInput()
  }

  override fun onConfigurationChanged(config: Configuration) {
    main.removeCallbacks(repeatDelete)
    super.onConfigurationChanged(config)
    if (isInputViewShown) setInputView(onCreateInputView())
  }

  private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

  private fun rounded(color: Int, radius: Int = 9) =
    GradientDrawable().apply {
      setColor(color)
      cornerRadius = dp(radius).toFloat()
    }

  override fun onCreateInputView(): View {
    dark =
      resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    panel = Color.parseColor(if (dark) "#181E2A" else "#EEF1F8")
    keyColor = Color.parseColor(if (dark) "#30394B" else "#FFFFFF")
    ink = Color.parseColor(if (dark) "#EDF2FF" else "#202B42")
    muted = Color.parseColor(if (dark) "#B4BFD4" else "#586780")
    accent = Color.parseColor(if (dark) "#B6C6FF" else "#3858D9")
    accentText = Color.parseColor(if (dark) "#172550" else "#FFFFFF")
    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(panel, 18)
        setPadding(dp(8), dp(8), dp(8), dp(8))
      }
    val header =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), dp(6))
      }
    header.addView(
      ImageView(this).apply {
        setImageResource(R.drawable.ic_launcher_legacy)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      },
      LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) },
    )
    val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    titles.addView(
      TextView(this).apply {
        text = getString(R.string.app_name)
        textSize = 15f
        setTextColor(ink)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
      }
    )
    status =
      TextView(this).apply {
        textSize = 10f
        setTextColor(muted)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        text = if (editorStarted) "远程输入已就绪 · 中文可切换系统输入法" else "等待可编辑控件"
      }
    titles.addView(status)
    header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(
      key("切换", dp(44), 1f, false, "切换到其他系统输入法") {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
      },
      LinearLayout.LayoutParams(dp(54), dp(44)).apply { marginStart = dp(6) },
    )
    header.addView(
      key("收起", dp(44), 1f, false, "收起键盘") { requestHideSelf(0) },
      LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(4) },
    )
    root.addView(header)
    body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(body)
    root.setOnApplyWindowInsetsListener { view, insets ->
      val bottom =
        if (android.os.Build.VERSION.SDK_INT >= 30)
          insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else insets.stableInsetBottom
      view.setPadding(dp(8), dp(8), dp(8), dp(8) + bottom)
      insets
    }
    renderKeys()
    root.requestApplyInsets()
    return root
  }

  private fun updateStatus(message: String) {
    status?.text = message
  }

  private class KeyView(context: Context) : TextView(context) {
    var onRelease: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
      if (
        event.actionMasked == MotionEvent.ACTION_UP ||
          event.actionMasked == MotionEvent.ACTION_CANCEL
      )
        onRelease?.invoke()
      return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
  }

  private fun key(
    label: String,
    height: Int,
    weight: Float = 1f,
    primary: Boolean = false,
    description: String = label,
    action: () -> Unit,
  ): KeyView =
    KeyView(this).apply {
      text = label
      gravity = Gravity.CENTER
      textSize = if (label.length > 2) 12f else 17f
      setTextColor(if (primary) accentText else ink)
      setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
      background =
        RippleDrawable(
          ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
          rounded(if (primary) accent else keyColor),
          null,
        )
      isClickable = true
      isFocusable = true
      contentDescription = description
      setPadding(0, 0, 0, 0)
      layoutParams =
        LinearLayout.LayoutParams(0, height, weight).apply {
          setMargins(dp(3), dp(3), dp(3), dp(3))
        }
      setOnClickListener {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        action()
      }
    }

  private fun row(keys: List<String>, height: Int) {
    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (value in keys) r.addView(
      key(if (shifted || capsLock) value.uppercase() else value, height) {
        type(if (shifted || capsLock) value.uppercase() else value)
        if (shifted && !capsLock) {
          shifted = false
          renderKeys()
        }
      }
    )
    body?.addView(r)
  }

  private fun renderKeys() {
    val container = body ?: return
    container.removeAllViews()
    val height =
      dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 36 else 44)
    val numeric = ImePolicy.numeric(currentInputEditorInfo?.inputType ?: 0)
    if (numeric) {
      row(listOf("1", "2", "3"), height)
      row(listOf("4", "5", "6"), height)
      row(listOf("7", "8", "9"), height)
      row(listOf("-", "0", "."), height)
    } else {
      row("1234567890".map { it.toString() }, height)
      if (symbols) {
        row(listOf("@", "#", "$", "%", "&", "*", "(", ")", "/", ":"), height)
        row(listOf("-", "_", "=", "+", "[", "]", "{", "}", "\\"), height)
        row(listOf("!", "?", "'", "\"", ";", ",", ".", "，", "。"), height)
      } else {
        row("qwertyuiop".map { it.toString() }, height)
        row("asdfghjkl".map { it.toString() }, height)
        val letters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shift =
          key(if (capsLock) "⇪" else "⇧", height, 1.4f, shifted || capsLock, "切换大小写") {
            shifted = !shifted
            capsLock = false
            renderKeys()
          }
        shift.setOnLongClickListener {
          capsLock = !capsLock
          shifted = capsLock
          renderKeys()
          true
        }
        letters.addView(shift)
        for (v in "zxcvbnm") letters.addView(
          key(if (shifted || capsLock) v.uppercase() else v.toString(), height) {
            type(if (shifted || capsLock) v.uppercase() else v.toString())
            if (shifted && !capsLock) {
              shifted = false
              renderKeys()
            }
          }
        )
        letters.addView(key(",", height) { type(",") })
        letters.addView(key(".", height) { type(".") })
        container.addView(letters)
      }
    }
    val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    if (numeric) controls.addView(key("+", height, 1.4f, false, "输入加号") { type("+") })
    else {
      controls.addView(
        key(if (symbols) "ABC" else "#+=", height, 1.4f, false, "切换英文与符号") {
          symbols = !symbols
          renderKeys()
        }
      )
    }
    controls.addView(key("空格", height, 3.5f, false, "输入空格") { type(" ") })
    val delete = key("⌫", height, 1.4f, false, "退格，长按连续删除") { deleteOne() }
    delete.setOnLongClickListener {
      repeatDelete.run()
      true
    }
    delete.onRelease = { main.removeCallbacks(repeatDelete) }
    controls.addView(delete)
    val info = currentInputEditorInfo
    val enter =
      key(ImePolicy.enterLabel(info?.inputType ?: 0, info?.imeOptions ?: 0), height, 1.7f, true) {
        enter()
      }
    enter.setOnLongClickListener {
      type("\n")
      true
    }
    controls.addView(enter)
    container.addView(controls)
  }

  private fun type(value: String) {
    if (currentInputConnection?.commitText(value, 1) == true) updateStatus("输入已提交 · 中文可切换系统输入法")
    else updateStatus("当前没有可编辑控件")
  }

  private fun deleteOne() {
    val c = currentInputConnection ?: return
    if (!c.deleteSurroundingTextInCodePoints(1, 0)) c.deleteSurroundingText(1, 0)
  }

  private fun enter() {
    val c = currentInputConnection ?: return
    val info = currentInputEditorInfo
    if (ImePolicy.newline(info?.inputType ?: 0, info?.imeOptions ?: 0)) {
      type("\n")
      return
    }
    if (!c.performEditorAction((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION)) {
      c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
      c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
  }

  fun commit(text: String) {
    if (text.length > 16000) throw BridgeError("INVALID_ARGUMENT", "文本最多 16000 字符")
    val result = CompletableFuture<Boolean>()
    val until = System.currentTimeMillis() + 2000
    val submit =
      object : Runnable {
        override fun run() {
          if (result.isDone) return
          val c = currentInputConnection
          if (editorStarted && c != null) {
            val ok = c.commitText(text, 1)
            if (ok) updateStatus("远程输入已提交")
            result.complete(ok)
          } else if (System.currentTimeMillis() < until) main.postDelayed(this, 50)
          else result.complete(false)
        }
      }
    main.post(submit)
    try {
      if (!result.get(2500, TimeUnit.MILLISECONDS))
        throw BridgeError("UNSUPPORTED", "当前页面没有可输入控件或拒绝辅助输入法")
    } finally {
      result.cancel(false)
      main.removeCallbacks(submit)
    }
  }
}
