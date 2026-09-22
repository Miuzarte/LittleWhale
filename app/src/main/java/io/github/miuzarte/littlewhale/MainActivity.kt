package io.github.miuzarte.littlewhale

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.miuzarte.littlewhale.channel.LwApps
import io.github.miuzarte.littlewhale.channel.LwOcr
import io.github.miuzarte.littlewhale.channel.PreviewControl
import io.github.miuzarte.littlewhale.channel.ScreenshotBudget
import io.github.miuzarte.littlewhale.host.DshHostService
import io.github.miuzarte.littlewhale.theme.ThemeStore
import io.github.miuzarte.littlewhale.ui.LittleWhaleApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        // 外观设置要在第一帧之前读出来, 否则会先闪一下默认主题
        ThemeStore.initialize(this)
        // 同理: 预览能不能当触摸板, 默认是关, 得在画画面之前就知道
        PreviewControl.initialize(this)
        // 截图缩到多少像素, 桥在第一次截图时就要用上
        ScreenshotBudget.initialize(this)
        // OCR 的模型是懒加载的 (第一次调它才建 session), 这里只把 context 挂上去
        LwOcr.attach(this)
        // 能启动哪些应用要问 PackageManager, 同样只挂 context
        LwApps.attach(this)
        // The host deliberately outlives this activity, so the service owns its lifetime
        DshHostService.start(this)
        setContent {
            LittleWhaleApp()
        }
    }

    /** Android 13 keeps the host notification hidden until the user grants this */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(permission)
    }
}
