package com.example.tvdashboard.boot

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.tvdashboard.BuildConfig

object HomeAssistSetup {
    private const val PREFS = "home_assist"
    fun enabled(context: Context) = BuildConfig.HOME_ASSIST_AVAILABLE &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    private fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
    fun status(context: Context): String = when {
        !BuildConfig.HOME_ASSIST_AVAILABLE -> "Bản tiêu chuẩn: chọn Home bằng hệ thống."
        !enabled(context) -> "Hỗ trợ chuyển về Dashboard: TẮT."
        !HomeAssistService.connected -> "Hỗ trợ Home: đã yêu cầu bật, dịch vụ trợ năng chưa kết nối."
        else -> "Hỗ trợ Home: dịch vụ đã kết nối; vẫn cần thử nút Home trên remote."
    }
    fun show(activity: Activity) {
        if (!BuildConfig.HOME_ASSIST_AVAILABLE) {
            AlertDialog.Builder(activity).setTitle("Bản tiêu chuẩn")
                .setMessage("Bản này không khai báo dịch vụ trợ năng. Nếu TV không cho đổi Home, quản trị viên có thể cung cấp bản homeAssistRelease để thử tính năng hỗ trợ. Tính năng này không bảo đảm hoạt động trên mọi firmware.")
                .setPositiveButton("Đóng", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Hỗ trợ tương thích Home")
            .setMessage(status(activity) + "\n\nKhi bật, dịch vụ trợ năng nhận tên ứng dụng và lớp cửa sổ của các launcher Home do hệ thống liệt kê. Khi cửa sổ Home cũ xuất hiện, app mở Dashboard. Không đọc nội dung màn hình, mật khẩu, phím nhập hoặc gửi sự kiện này lên server.\n\nĐây là chuyển hướng, không phải đổi Home mặc định. Home cũ có thể lóe lên. Android/Play Protect có thể hạn chế dịch vụ của APK cài ngoài. Không tắt Play Protect để bật tính năng.\n\nBạn phải tự bật TV Dashboard – Hỗ trợ Home trong Cài đặt trợ năng. Có thể tắt tại đây hoặc trong Cài đặt hệ thống bất kỳ lúc nào.")
            .setPositiveButton("Đồng ý và mở trợ năng") { _, _ ->
                setEnabled(activity, true)
                try { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) {
                    setEnabled(activity, false)
                    AlertDialog.Builder(activity).setMessage("Không mở được Cài đặt trợ năng. Hỗ trợ vẫn tắt.")
                        .setPositiveButton("Đóng", null).show()
                }
            }
            .setNeutralButton("Tắt hỗ trợ") { _, _ ->
                setEnabled(activity, false)
                HomeAssistService.stopSupport()
            }
            .setNegativeButton("Đóng", null).show()
    }
}
