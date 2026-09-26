package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.rodrigo.ultrazoom.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  data class DrawnText(
    val content: String,
    val x: Float,
    val y: Float,
    val textSize: Float,
    val width: Float,
    val bounds: RectF
  )

  class AuditCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    val texts = mutableListOf<DrawnText>()

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
      super.drawText(text, x, y, paint)
      val w = paint.measureText(text)
      val h = paint.textSize
      val box = RectF(x + 2f, y - h * 0.65f, x + w - 2f, y + h * 0.10f)
      texts.add(DrawnText(text, x, y, h, w, box))
    }
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("UltraZoom", appName)
  }

  @Test
  fun `visual screenshot audit across resolutions and hardware profiles verifies zero text collision and readable typography`() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).create()
    val activity = controller.get()
    val view = activity.cameraView

    val resolutions = listOf(
      1080 to 2400, // 20:9 modern flagship
      1080 to 1920, // 16:9 standard FHD
      720 to 1280,  // compact HD
      1440 to 3200  // WQHD+ ultra flagship
    )

    val profiles = listOf(
      Triple(false, 1.0f, 1.0f) to "BASE_1X",
      Triple(true, 10.0f, 2.4f) to "FLAGSHIP_10X"
    )

    for ((profile, profileName) in profiles) {
      activity.populateSampleHardwareStateForAudit(profile.first, profile.second, profile.third)

      for ((w, h) in resolutions) {
        view.measure(
          View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)

        assertTrue(
          "Layout must be valid in $profileName at ${w}x${h}, got: ${activity.hudLayoutDiagnostic}",
          activity.isHudLayoutValid
        )
        assertEquals("OK", activity.hudLayoutDiagnostic)

        val mini = activity.miniRect
        val zoomArea = activity.zoomAreaRect
        val zoomBadge = activity.zoomBadgeRect
        val telemetry = activity.telemetryCardRect
        val scale = activity.uiScale

        assertFalse("Minimap must not intersect zoomArea at ${w}x${h}", RectF.intersects(mini, zoomArea))
        assertFalse("Minimap must not intersect zoomBadge at ${w}x${h}", RectF.intersects(mini, zoomBadge))
        assertFalse("Minimap must not intersect telemetryCard at ${w}x${h}", RectF.intersects(mini, telemetry))
        for (i in 0 until 5) {
          val lensRect = activity.getLensRect(i)
          assertFalse("Minimap must not intersect lens[$i] at ${w}x${h}", RectF.intersects(mini, lensRect))
        }
        assertTrue(
          "Minimap bottom (${mini.bottom}) must be well above lens buttons (${activity.getLensRect(0).top}) at ${w}x${h}",
          mini.bottom + 120f * scale < activity.getLensRect(0).top
        )

        val states = listOf(
          Triple(false, false, false) to "MAIN_HUD",
          Triple(true, false, false) to "DIAGNOSTIC_INFO",
          Triple(false, true, false) to "MODE_SHEET",
          Triple(false, false, true) to "CAMERA_MENU"
        )

        for ((triple, stateName) in states) {
          activity.setModalStateForAudit(triple.first, triple.second, triple.third)
          val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
          val canvas = AuditCanvas(bmp)
          view.draw(canvas)

          assertTrue("Expected drawn texts in $profileName/$stateName at ${w}x${h}", canvas.texts.isNotEmpty())

          val minAllowedFontSize = 16.0f * scale
          for (dt in canvas.texts) {
            assertTrue(
              "Text '${dt.content}' in $profileName/$stateName at ${w}x${h} is too small (${dt.textSize}px < ${minAllowedFontSize}px)",
              dt.textSize >= minAllowedFontSize
            )
            assertTrue(
              "Text '${dt.content}' in $profileName/$stateName at ${w}x${h} overflows left edge (x=${dt.x})",
              dt.x >= -2f
            )
            assertTrue(
              "Text '${dt.content}' in $profileName/$stateName at ${w}x${h} overflows right edge (right=${dt.x + dt.width} > $w)",
              dt.x + dt.width <= w + 4f
            )
          }

          // Verify zero text-to-text collision across ALL 4 screens (MAIN_HUD, DIAGNOSTIC_INFO, MODE_SHEET, CAMERA_MENU)
          for (i in canvas.texts.indices) {
            for (j in i + 1 until canvas.texts.size) {
              val a = canvas.texts[i]
              val b = canvas.texts[j]
              assertFalse(
                "Text collision in $profileName/$stateName at ${w}x${h}: '${a.content}' (${a.bounds}) overlaps '${b.content}' (${b.bounds})",
                RectF.intersects(a.bounds, b.bounds)
              )
            }
          }

          if (stateName == "MAIN_HUD") {
            // Verify only the minimap's own footer label is inside miniRect
            for (dt in canvas.texts) {
              if (RectF.intersects(dt.bounds, mini)) {
                assertTrue(
                  "Only the minimap's own footer label may appear inside miniRect, found '${dt.content}' at ${w}x${h}",
                  dt.content.startsWith("MAPA") || dt.content.startsWith("FOV")
                )
              }
            }
            // Verify all 5 lens pills have distinct labels
            val lensLabels = (0 until 5).mapNotNull { idx ->
              val lr = activity.getLensRect(idx)
              canvas.texts.firstOrNull { RectF.intersects(it.bounds, lr) }?.content
            }
            assertEquals("Expected 5 distinct lens labels in $profileName at ${w}x${h}", 5, lensLabels.distinct().size)
          }

          bmp.recycle()
        }
      }
    }

    // Reset modal state and test touch interaction on telemetry card to open Diagnostic Info
    activity.setModalStateForAudit(false, false, false)
    val telemetryCenter = activity.telemetryCardRect
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, telemetryCenter.centerX(), telemetryCenter.centerY(), 0)
    val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, telemetryCenter.centerX(), telemetryCenter.centerY(), 0)
    view.dispatchTouchEvent(down)
    view.dispatchTouchEvent(up)
    down.recycle()
    up.recycle()

    controller.pause().stop().destroy()
  }
}
