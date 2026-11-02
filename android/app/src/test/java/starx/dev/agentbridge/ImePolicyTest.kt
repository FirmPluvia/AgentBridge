package starx.dev.agentbridge

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class ImePolicyTest {
  @Test
  fun multilineEditorKeepsNewlines() {
    val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    assertTrue(ImePolicy.newline(type, EditorInfo.IME_ACTION_DONE))
    assertEquals("换行", ImePolicy.enterLabel(type, EditorInfo.IME_ACTION_DONE))
  }

  @Test
  fun nextAndDoneMatchEditorActions() {
    assertFalse(ImePolicy.newline(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT))
    assertEquals("下一项", ImePolicy.enterLabel(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT))
    assertEquals("完成", ImePolicy.enterLabel(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE))
  }

  @Test
  fun noEnterActionFlagIsRespected() {
    assertTrue(
      ImePolicy.newline(
        InputType.TYPE_CLASS_TEXT,
        EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
      )
    )
  }

  @Test
  fun numericAndPhoneEditorsSelectNumbers() {
    assertTrue(ImePolicy.numeric(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
    assertTrue(ImePolicy.numeric(InputType.TYPE_CLASS_PHONE))
    assertFalse(ImePolicy.numeric(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
  }
}
