package com.aliothmoon.maafw.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

// 精密极简工业（Titanium Silver / Anodized Dark Titanium）中性色阶
private val LightBackground = Color(0xFFECECEF)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE4E4E8)
private val LightOnSurface = Color(0xFF111113)
private val LightOnSurfaceVariant = Color(0xFF63636B)
private val LightOutline = Color(0xFFD8D8DF)

private val DarkBackground = Color(0xFF09090B)
private val DarkSurface = Color(0xFF151519)
private val DarkSurfaceVariant = Color(0xFF1E1E24)
private val DarkOnSurface = Color(0xFFF4F4F5)
private val DarkOnSurfaceVariant = Color(0xFF91919B)
private val DarkOutline = Color(0xFF27272F)

private fun createLightColorScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = Color(0xFF52525B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E4E8),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFFC26D0A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F7),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9E9EE),
    surfaceContainerHighest = Color(0xFFDCDCE3),
    outline = LightOutline,
    outlineVariant = Color(0xFFE6E6EC),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private fun createDarkColorScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF09090B),
    secondaryContainer = Color(0xFF202026),
    onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF1C1404),
    tertiaryContainer = Color(0xFF452B06),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF060608),
    surfaceContainerLow = Color(0xFF111115),
    surfaceContainer = Color(0xFF17171C),
    surfaceContainerHigh = Color(0xFF212128),
    surfaceContainerHighest = Color(0xFF2B2B34),
    outline = DarkOutline,
    outlineVariant = Color(0xFF1E1E25),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF451215),
    onErrorContainer = Color(0xFFFECACA)
)

private val PrecisionLight = createLightColorScheme(
    primary = Color(0xFF18181B),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFE4E4EA),
    onPrimaryContainer = Color(0xFF111113),
)

private val PrecisionDark = createDarkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF09090B),
    primaryContainer = Color(0xFF272730),
    onPrimaryContainer = Color(0xFFF4F4F5),
)

/** 保留枚举兼容旧 DataStore 持久化；统一映射到精密极简工业体系 */
enum class ThemeStyle { DEFAULT, SEMI_DESIGN }

/** 徽章/状态用的语义配色对：content + container */
data class MaaTone(val content: Color, val container: Color)

/** M3 colorScheme 之外的语义扩展色（成功/警示/信息/强调），随明暗切换 */
data class MaaPalette(
    val success: MaaTone,
    val warning: MaaTone,
    val info: MaaTone,
    val violet: MaaTone,
    val neutral: MaaTone,
    val switchOff: Color,
) {
    /** 按稳定顺序取一组强调色，用于分组徽章、任务色条等 */
    val accents: List<MaaTone> get() = listOf(info, violet, warning, success)
}

private val LightMaaPalette = MaaPalette(
    success = MaaTone(Color(0xFF059669), Color(0xFFD1FAE5)),
    warning = MaaTone(Color(0xFFC26D0A), Color(0xFFFEF3C7)),
    info = MaaTone(Color(0xFF0284C7), Color(0xFFE0F2FE)),
    violet = MaaTone(Color(0xFF7C3AED), Color(0xFFEDE9FE)),
    neutral = MaaTone(Color(0xFF63636B), Color(0xFFEAEAEF)),
    switchOff = Color(0xFF9A9AA3),
)

private val DarkMaaPalette = MaaPalette(
    success = MaaTone(Color(0xFF34D399), Color(0xFF0A2E22)),
    warning = MaaTone(Color(0xFFFBBF24), Color(0xFF3B2608)),
    info = MaaTone(Color(0xFF38BDF8), Color(0xFF0C2D48)),
    violet = MaaTone(Color(0xFFA78BFA), Color(0xFF2D1B54)),
    neutral = MaaTone(Color(0xFF91919B), Color(0xFF222229)),
    switchOff = Color(0xFF787885),
)

val LocalMaaPalette = staticCompositionLocalOf { LightMaaPalette }

val LocalMaaStyleTokens = staticCompositionLocalOf { DefaultStyleTokens }

val LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.DEFAULT }

val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** 主题扩展读取入口；Screen 不直接碰 DataStore / ThemeStyle 分支 */
object MaaTheme {
    val palette: MaaPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalMaaPalette.current

    val style: MaaStyleTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalMaaStyleTokens.current

    val themeStyle: ThemeStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeStyle.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalIsDarkTheme.current
}

private fun shapesOf(tokens: MaaStyleTokens): Shapes = Shapes(
    extraSmall = RoundedCornerShape(tokens.radii.inner),
    small = RoundedCornerShape(tokens.radii.button),
    medium = RoundedCornerShape(tokens.radii.card),
    large = RoundedCornerShape(tokens.radii.large),
    extraLarge = RoundedCornerShape(tokens.radii.large),
)

private fun colorSchemeOf(style: ThemeStyle, dark: Boolean): ColorScheme = when (style) {
    ThemeStyle.DEFAULT, ThemeStyle.SEMI_DESIGN -> if (dark) PrecisionDark else PrecisionLight
}

private fun paletteOf(style: ThemeStyle, dark: Boolean): MaaPalette = when (style) {
    ThemeStyle.DEFAULT, ThemeStyle.SEMI_DESIGN -> if (dark) DarkMaaPalette else LightMaaPalette
}

private object NoIndication : IndicationNodeFactory {
    private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return NoIndicationNode()
    }

    override fun hashCode(): Int = -1
    override fun equals(other: Any?): Boolean = other === this
}

@Composable
fun MaaFwTheme(
    themeStyle: ThemeStyle = ThemeStyle.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val styleTokens = styleTokensOf(themeStyle)
    val colorScheme = colorSchemeOf(themeStyle, darkTheme)
    val palette = paletteOf(themeStyle, darkTheme)

    CompositionLocalProvider(
        // maaClickable 自带按压缩放反馈；foundation 层 plain clickable 不再叠加涟漪
        LocalIndication provides NoIndication,
        LocalMaaPalette provides palette,
        LocalMaaStyleTokens provides styleTokens,
        LocalThemeStyle provides themeStyle,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = shapesOf(styleTokens),
            content = content
        )
    }
}
