package network.tos.wallet

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import network.tos.wallet.app.ui.screen.root.RootActivity
import network.tos.wallet.app.ui.screen.pq.V5R2WalletsScreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V5R2ScreenTest {
    @Test fun initialR2ScreenShowsPendingGatesAndProtectsWindow() {
        ActivityScenario.launch(RootActivity::class.java).use { scenario ->
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            var initialized = false
            while (!initialized && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { initialized = it.isInitialized }
                if (!initialized) android.os.SystemClock.sleep(100)
            }
            assertTrue("Root navigation did not initialize", initialized)
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.add(V5R2WalletsScreen())
                activity.supportFragmentManager.executePendingTransactions()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val imported = device.wait(Until.findObject(By.desc("v5r2.import")), 10000)
            var fragments = ""
            scenario.onActivity { activity ->
                fragments = activity.supportFragmentManager.fragments.joinToString { "${it.javaClass.simpleName}:visible=${it.isVisible}:view=${it.view != null}" }
                val screen = activity.supportFragmentManager.fragments.filterIsInstance<V5R2WalletsScreen>().singleOrNull()
                fun controls(v: android.view.View): String {
                    val own = if (v.contentDescription?.toString() == "v5r2.import") {
                        val rect = android.graphics.Rect(); val visible = v.getGlobalVisibleRect(rect)
                        ";import:shown=${v.isShown}:size=${v.width}x${v.height}:rect=$rect:visible=$visible:alpha=${v.alpha}"
                    } else ""
                    return own + if (v is android.view.ViewGroup) (0 until v.childCount).joinToString("") { controls(v.getChildAt(it)) } else ""
                }
                fragments += screen?.view?.let { controls(it) }.orEmpty()
            }
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            fun texts(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
                if (node == null) return emptyList()
                val own = node.text?.toString()?.let { listOf(it.take(300)) }.orEmpty()
                return own + (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            }
            val overlay = if (root?.packageName?.toString() == "android") texts(root).joinToString(" | ") else ""
            assertNotNull("R2 import button missing; package=${device.currentPackageName}; overlay=$overlay; $fragments", imported)
            assertNotNull(device.findObject(By.text("V5R2 accounts")))
            assertNotNull(device.findObject(By.textContains("Network verification and recovery funding are pending")))
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }
}
