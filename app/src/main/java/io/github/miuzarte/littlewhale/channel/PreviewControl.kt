package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 预览画面上的手指算不算操作那块屏
 *
 * **默认关**, 而且是有意关着: 这块屏是给模型用的, 手机就摆在手边, 手指滑过画面就会把模型正在做的事
 * 打乱 (点歪一个按钮, 或者把它刚打开的东西划走)。关着的时候画面只看不动 - 要看菜单还是碰画面,
 * 那只是叫出悬浮按钮, 不会送到屏上去
 *
 * 要自己上手玩那块屏, 就在设置页「虚拟屏」里打开
 */
object PreviewControl {

    /** 与 [io.github.miuzarte.littlewhale.host.HostSettings] 同一个偏好文件, 全 app 一个 */
    private const val STORE = "littlewhale"

    private const val ALLOWED = "preview-control"

    /** 当前值, 预览的手势层读它, 所以是 Compose 状态 */
    var allowed: Boolean by mutableStateOf(false)
        private set

    /** 从磁盘读一次, 在第一帧之前调, 免得先按默认值画一遍再跳 */
    fun initialize(context: Context) {
        allowed = preferences(context).getBoolean(ALLOWED, false)
    }

    /** 收下新的选择, 先让界面用上再落盘 */
    fun set(context: Context, allowedValue: Boolean) {
        allowed = allowedValue
        preferences(context).edit().putBoolean(ALLOWED, allowedValue).apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
