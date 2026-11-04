package com.example.tvdashboard.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tvdashboard.ui.MainActivity

/**
 * Nhận BOOT_COMPLETED (và QUICKBOOT_POWERON ở một số box) rồi mở MainActivity.
 *
 * Lưu ý: từ Android 10, app ở nền bị hạn chế mở Activity. Cách khắc phục chính là cấp
 * quyền "Hiển thị trên ứng dụng khác" (SYSTEM_ALERT_WINDOW), xem docs/AUTOSTART.md.
 * App cũng phải được mở thủ công ít nhất một lần sau khi cài thì mới nhận được broadcast.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Nhận broadcast: $action")

        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) {
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launchIntent)
            Log.i(TAG, "Đã gửi yêu cầu mở MainActivity")
        } catch (e: Exception) {
            // Có thể bị hệ thống chặn (hạn chế mở Activity từ nền) hoặc hãng tùy biến.
            Log.e(TAG, "Không mở được MainActivity", e)
        }
    }

    private companion object {
        const val TAG = "TvBootReceiver"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}