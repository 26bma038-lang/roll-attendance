package app.roll.screenshot

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.roll.core.designsystem.theme.RollTheme
import app.roll.feature.gallery.GalleryScreen
import app.roll.feature.gallery.GalleryUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Renders the design-system gallery on the JVM so the design can be reviewed without a device.
 * Record with `./gradlew recordRoborazziDebug`; images land in app/build/outputs/roborazzi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h2600dp-xxhdpi", application = Application::class)
class DesignSystemScreenshotTest {

    @Test
    fun gallery_light() = capture("gallery_light", darkTheme = false)

    @Test
    fun gallery_dark() = capture("gallery_dark", darkTheme = true)

    @Test
    @Config(qualifiers = "w400dp-h2600dp-xxhdpi", fontScale = 2f)
    fun gallery_largeFont() = capture("gallery_font200", darkTheme = false)

    private fun capture(name: String, darkTheme: Boolean) {
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            Gallery(darkTheme)
        }
    }

    @Composable
    private fun Gallery(darkTheme: Boolean) {
        RollTheme(darkTheme = darkTheme) {
            Box(Modifier.fillMaxSize()) {
                GalleryScreen(
                    uiState = GalleryUiState(),
                    today = LocalDate.of(2026, 11, 5),
                    onMarkChange = { _, _ -> },
                    onUndoClear = {},
                    onUndoDismissed = {},
                    onNavSelect = {},
                    onThemeModeChange = {},
                )
            }
        }
    }
}
