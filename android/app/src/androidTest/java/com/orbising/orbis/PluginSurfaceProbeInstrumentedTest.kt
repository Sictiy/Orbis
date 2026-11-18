package com.orbising.orbis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated feasibility probe, not an implementation of the plugin protocol. */
class PluginSurfaceProbeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteSurfaceRendersReceivesInputAndCanBeReleased() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val remote = AtomicReference<IBinder>()
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { remote.set(service); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { remote.set(null) }
        }
        val intent = Intent().setClassName("com.orbising.orbis.plugintest", "com.orbising.orbis.plugintest.SurfaceProbeService")
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        val viewRef = AtomicReference<SurfaceView>()
        val hostClicked = AtomicBoolean(false)
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            compose.setContent { Column {
                AndroidView(factory = { SurfaceView(it).apply { setZOrderOnTop(true) }.also(viewRef::set) }, modifier = Modifier.fillMaxWidth().height(240.dp))
                Button(onClick = { hostClicked.set(true) }) { Text("Host tab") }
            } }
            compose.waitUntil(10_000) { viewRef.get()?.let { it.isAttachedToWindow && it.width > 0 && it.height > 0 && it.hostToken != null } == true }
            val view = viewRef.get()
            val binder = remote.get()
            fun create() {
                val request = Parcel.obtain(); val response = Parcel.obtain()
                try {
                    request.writeStrongBinder(view.hostToken); request.writeInt(view.display.displayId); request.writeInt(view.width); request.writeInt(view.height)
                    assertTrue(binder.transact(1, request, response, 0)); response.readException()
                    @Suppress("DEPRECATION") val pkg = response.readParcelable<SurfaceControlViewHost.SurfacePackage>(SurfaceControlViewHost.SurfacePackage::class.java.classLoader)!!
                    compose.runOnIdle { view.setChildSurfacePackage(pkg) }
                } finally { request.recycle(); response.recycle() }
            }
            fun state(): Pair<Int, String?> {
                val request = Parcel.obtain(); val response = Parcel.obtain()
                try { assertTrue(binder.transact(2, request, response, 0)); response.readException(); return response.readInt() to response.readString() }
                finally { request.recycle(); response.recycle() }
            }
            fun release() {
                val request = Parcel.obtain(); val response = Parcel.obtain()
                try { assertTrue(binder.transact(3, request, response, 0)); response.readException() }
                finally { request.recycle(); response.recycle() }
            }
            fun touch(relativeY: Float) {
                val location = IntArray(2)
                compose.runOnIdle { view.getLocationOnScreen(location) }
                val x = location[0] + view.width / 2f; val y = location[1] + relativeY * context.resources.displayMetrics.density
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0)
                down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(down, true)); assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(up, true)) }
                finally { down.recycle(); up.recycle() }
            }
            create()
            SystemClock.sleep(1000)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "surface-probe.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            touch(36f)
            compose.waitUntil(5_000) { state().first == 1 }
            compose.onNodeWithText("Host tab").performTouchInput { click() }
            assertTrue(hostClicked.get())
            touch(72f)
            InstrumentationRegistry.getInstrumentation().sendStringSync("probe")
            compose.waitUntil(5_000) { state().second == "probe" }
            release()
            create()
            touch(36f)
            compose.waitUntil(5_000) { state().first == 2 }
            val died = CountDownLatch(1)
            binder.linkToDeath({ died.countDown() }, 0)
            val request = Parcel.obtain(); val response = Parcel.obtain()
            try { assertTrue(binder.transact(4, request, response, 0)); response.readException() }
            finally { request.recycle(); response.recycle() }
            assertTrue(died.await(5, TimeUnit.SECONDS))
        } finally { context.unbindService(connection) }
    }
}
