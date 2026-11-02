package starx.dev.agentbridge

import android.text.InputType
import android.view.inputmethod.EditorInfo

object ImePolicy {
  fun numeric(inputType: Int) =
    inputType and InputType.TYPE_MASK_CLASS in
      setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)

  fun newline(inputType: Int, imeOptions: Int): Boolean =
    (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
      inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) ||
      imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
      imeOptions and EditorInfo.IME_MASK_ACTION in
        setOf(EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED)

  fun enterLabel(inputType: Int, imeOptions: Int): String =
    if (newline(inputType, imeOptions)) "换行"
    else
      when (imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_NEXT -> "下一项"
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_SEND -> "发送"
        else -> "完成"
      }
}
