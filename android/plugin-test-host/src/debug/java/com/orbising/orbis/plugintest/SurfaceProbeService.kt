package com.orbising.orbis.plugintest

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.Parcel
import android.os.Process
import android.view.SurfaceControlViewHost
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only cross-process rendering experiment. Not a production plugin API. */
class SurfaceProbeService : Service() {
    private var surfaceHost: SurfaceControlViewHost? = null
    private var clicks = 0
    private var input: EditText? = null
    private val main = Handler(Looper.getMainLooper())
    private fun <T> onMain(block: () -> T): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        main.post { result = runCatching(block); done.countDown() }
        check(done.await(5, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (packageManager.checkSignatures(Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) throw SecurityException()
            return when (code) {
                1 -> {
                    val token = data.readStrongBinder() ?: return false
                    val displayId = data.readInt(); val width = data.readInt(); val height = data.readInt()
                    val pkg = onMain {
                        surfaceHost?.release()
                        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId)
                        val displayContext = createDisplayContext(display)
                        val remote = SurfaceControlViewHost(displayContext, display, token)
                        val root = LinearLayout(displayContext).apply { orientation = LinearLayout.VERTICAL }
                        root.addView(Button(displayContext).apply { text = "Plugin button"; setOnClickListener { clicks++ } })
                        root.addView(EditText(displayContext).apply { hint = "Plugin input"; input = this })
                        remote.setView(root, width, height)
                        surfaceHost = remote
                        remote.surfacePackage
                    }
                    reply!!.writeNoException(); reply.writeParcelable(pkg, 0); true
                }
                2 -> { val values = onMain { clicks to input?.text.toString() }; reply!!.writeNoException(); reply.writeInt(values.first); reply.writeString(values.second); true }
                3 -> { onMain { surfaceHost?.release(); surfaceHost = null; input = null }; reply!!.writeNoException(); true }
                4 -> { reply!!.writeNoException(); main.postDelayed({ Process.killProcess(Process.myPid()) }, 100); true }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { surfaceHost?.release(); surfaceHost = null; super.onDestroy() }
}
