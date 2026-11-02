package dev.agentbridge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

val LocalMiuix = staticCompositionLocalOf { false }
val LocalClassicHome = staticCompositionLocalOf { false }

private val MaterialShapes =
  Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )
private val MaterialTypography =
  Typography().run {
    copy(
      headlineSmall =
        headlineSmall.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
      titleLarge = titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
      titleMedium =
        titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
      bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
      bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
      bodySmall = bodySmall.copy(fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
      labelLarge = labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
      labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
    )
  }

/** Only miuix Home retains the prior Material home; other miuix pages keep their components. */
@Composable
fun ClassicMaterialHome(content: @Composable () -> Unit) {
  val colors =
    if (isSystemInDarkTheme())
      darkColorScheme(
        primary = Color(0xFFA9CCFF),
        onPrimary = Color(0xFF003162),
        primaryContainer = Color(0xFF173C68),
        onPrimaryContainer = Color(0xFFD6E8FF),
        secondaryContainer = Color(0xFF244464),
        onSecondaryContainer = Color(0xFFD6E8FF),
        background = Color(0xFF10151D),
        surface = Color(0xFF19212C),
        surfaceVariant = Color(0xFF253040),
        outlineVariant = Color(0xFF354354),
      )
    else
      lightColorScheme(
        primary = Color(0xFF287BFA),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8F1FF),
        onPrimaryContainer = Color(0xFF123C73),
        secondaryContainer = Color(0xFFDCEAFF),
        onSecondaryContainer = Color(0xFF13447E),
        background = Color(0xFFF5F7FB),
        surface = Color.White,
        surfaceVariant = Color(0xFFEDF2F8),
        outlineVariant = Color(0xFFDFE6F0),
      )
  CompositionLocalProvider(LocalMiuix provides false, LocalClassicHome provides true) {
    MaterialTheme(
      colorScheme = colors,
      typography = Typography(),
      shapes = Shapes(),
      content = content,
    )
  }
}

@Composable
fun BridgeTheme(style: String, content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val miuix = style == "miuix"
  val miuixColors = if (dark) miuixDark() else miuixLight()
  val materialColors =
    if (dark)
      darkColorScheme(
        primary = Color(0xFFB3D2FF),
        secondary = Color(0xFFB5C9E4),
        onSecondary = Color(0xFF20344E),
        onBackground = Color(0xFFE5EBF4),
        onSurface = Color(0xFFE5EBF4),
        onSurfaceVariant = Color(0xFFBFCADA),
        outline = Color(0xFF8794A7),
        surfaceTint = Color(0xFFB3D2FF),
        surfaceContainerLowest = Color(0xFF0D1219),
        surfaceContainerLow = Color(0xFF19212C),
        surfaceContainer = Color(0xFF1D2632),
        surfaceContainerHigh = Color(0xFF26313F),
        surfaceContainerHighest = Color(0xFF303D4D),
        onPrimary = Color(0xFF003162),
        primaryContainer = Color(0xFF173C68),
        onPrimaryContainer = Color(0xFFD6E8FF),
        secondaryContainer = Color(0xFF244464),
        onSecondaryContainer = Color(0xFFD6E8FF),
        background = Color(0xFF10151D),
        surface = Color(0xFF19212C),
        surfaceVariant = Color(0xFF253040),
        outlineVariant = Color(0xFF354354),
      )
    else
      lightColorScheme(
        primary = Color(0xFF2867D8),
        secondary = Color(0xFF4C6280),
        onSecondary = Color.White,
        onBackground = Color(0xFF182230),
        onSurface = Color(0xFF182230),
        onSurfaceVariant = Color(0xFF4E5E73),
        outline = Color(0xFF738398),
        surfaceTint = Color(0xFF2867D8),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFBFCFF),
        surfaceContainer = Color(0xFFEEF3FA),
        surfaceContainerHigh = Color(0xFFE7EEF8),
        surfaceContainerHighest = Color(0xFFDEE8F5),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE8F1FF),
        onPrimaryContainer = Color(0xFF123C73),
        secondaryContainer = Color(0xFFDCEAFF),
        onSecondaryContainer = Color(0xFF13447E),
        background = Color(0xFFF5F7FB),
        surface = Color.White,
        surfaceVariant = Color(0xFFEDF2F8),
        outlineVariant = Color(0xFFDFE6F0),
      )
  val colors =
    if (miuix)
      materialColors.copy(
        primary = miuixColors.primary,
        onPrimary = miuixColors.onPrimary,
        primaryContainer = miuixColors.primaryContainer,
        onPrimaryContainer = miuixColors.onPrimaryContainer,
        background = miuixColors.background,
        onBackground = miuixColors.onBackground,
        surface = miuixColors.surface,
        onSurface = miuixColors.onSurface,
        surfaceVariant = miuixColors.secondaryContainer,
        onSurfaceVariant = miuixColors.onSurfaceVariantSummary,
        outlineVariant = miuixColors.dividerLine,
      )
    else materialColors
  CompositionLocalProvider(LocalMiuix provides miuix) {
    MiuixTheme(colors = miuixColors) {
      MaterialTheme(
        colorScheme = colors,
        shapes = MaterialShapes,
        typography = if (miuix) Typography() else MaterialTypography,
      ) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
          Box(Modifier.fillMaxSize()) {
            content()
            // Material Scaffold does not mount miuix's overlay host. Keep one at the root.
            if (miuix) top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost()
          }
        }
      }
    }
  }
}

@Composable
fun BridgeButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  colors: ButtonColors = ButtonDefaults.buttonColors(),
  shape: Shape = RoundedCornerShape(16.dp),
  content: @Composable RowScope.() -> Unit,
) {
  if (LocalMiuix.current) {
    top.yukonga.miuix.kmp.basic.Button(
      onClick,
      modifier,
      enabled,
      minHeight = 48.dp,
      colors =
        top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
          color = colors.containerColor,
          disabledColor = colors.disabledContainerColor,
        ),
    ) {
      CompositionLocalProvider(
        LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor
      ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
          content()
        }
      }
    }
  } else
    Button(
      onClick,
      modifier.heightIn(min = 48.dp),
      enabled,
      shape = shape,
      colors = colors,
      contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
      ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
        content()
      }
    }
}

@Composable
fun BridgeOutlinedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable RowScope.() -> Unit,
) {
  if (LocalMiuix.current) {
    top.yukonga.miuix.kmp.basic.Button(onClick, modifier, enabled, minHeight = 48.dp) {
      CompositionLocalProvider(
        LocalContentColor provides MiuixTheme.colorScheme.onSecondaryVariant
      ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
          content()
        }
      }
    }
  } else if (LocalClassicHome.current) OutlinedButton(onClick, modifier, enabled, content = content)
  else
    OutlinedButton(
      onClick,
      modifier.heightIn(min = 48.dp),
      enabled,
      shape = MaterialTheme.shapes.medium,
      border =
        androidx.compose.foundation.BorderStroke(
          1.dp,
          MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else .38f),
        ),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
      ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
        content()
      }
    }
}

@Composable
fun BridgeTextButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
  content: @Composable RowScope.() -> Unit,
) {
  if (LocalMiuix.current) {
    top.yukonga.miuix.kmp.basic.Button(
      onClick,
      modifier,
      enabled,
      insideMargin = contentPadding,
      colors =
        top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
          Color.Transparent,
          Color.Transparent,
        ),
    ) {
      CompositionLocalProvider(
        LocalContentColor provides
          MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
      ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
          content()
        }
      }
    }
  } else
    TextButton(
      onClick,
      modifier.heightIn(min = 48.dp),
      enabled,
      shape = MaterialTheme.shapes.small,
      contentPadding = contentPadding,
    ) {
      ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
        content()
      }
    }
}

@Composable
fun BridgeChoice(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  if (LocalMiuix.current) {
    val colors = MiuixTheme.colorScheme
    top.yukonga.miuix.kmp.basic.Button(
      onClick,
      modifier,
      enabled,
      colors =
        top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(
          if (selected) colors.primary else colors.secondaryVariant
        ),
    ) {
      CompositionLocalProvider(
        LocalContentColor provides if (selected) colors.onPrimary else colors.onSecondaryVariant
      ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center)) {
          label()
        }
      }
    }
  } else
    FilterChip(
      selected = selected,
      onClick = onClick,
      label = label,
      modifier = modifier,
      enabled = enabled,
    )
}

@Composable
fun BridgeAlertDialog(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  title: @Composable () -> Unit,
  text: @Composable () -> Unit,
  dismissButton: (@Composable () -> Unit)? = null,
) {
  if (LocalMiuix.current) {
    val show = remember { mutableStateOf(true) }
    top.yukonga.miuix.kmp.extra.SuperDialog(show = show, onDismissRequest = onDismissRequest) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          dismissButton?.invoke()
          confirmButton()
        }
      }
    }
  } else
    AlertDialog(
      onDismissRequest = onDismissRequest,
      confirmButton = confirmButton,
      title = title,
      text = text,
      dismissButton = dismissButton,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeChoices(
  options: List<Pair<String, String>>,
  selected: String,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (LocalMiuix.current) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      options.forEach { (value, label) ->
        BridgeChoice(
          selected = selected == value,
          onClick = { onSelect(value) },
          modifier =
            Modifier.weight(1f).heightIn(min = 48.dp).semantics {
              this.selected = selected == value
            },
          label = { Text(label, textAlign = TextAlign.Center) },
        )
      }
    }
  } else {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().heightIn(min = 48.dp)) {
      options.forEachIndexed { index, (value, label) ->
        SegmentedButton(
          selected = selected == value,
          onClick = { onSelect(value) },
          shape = SegmentedButtonDefaults.itemShape(index, options.size),
          modifier = Modifier.weight(1f).heightIn(min = 48.dp),
          icon = {},
          colors =
            SegmentedButtonDefaults.colors(
              activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
              activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              activeBorderColor = MaterialTheme.colorScheme.outline,
              inactiveContainerColor = MaterialTheme.colorScheme.surface,
              inactiveContentColor = MaterialTheme.colorScheme.onSurface,
              inactiveBorderColor = MaterialTheme.colorScheme.outline,
            ),
          label = { Text(label, textAlign = TextAlign.Center) },
        )
      }
    }
  }
}
