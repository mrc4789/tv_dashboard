package com.example.tvdashboard.boot

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** Registers a TV Home candidate. Only the system/user can grant the default Home role. */
object HomeLauncherSetup {
    fun isTv(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

    private fun homeAlias(context: Context) = ComponentName(context.packageName, context.packageName + ".HomeLauncher")

    fun registerTvCandidate(context: Context) {
        if (!isTv(context)) return // Automatic registration remains TV-only.
        registerCandidate(context)
    }

    private fun registerCandidate(context: Context) {
        val alias = homeAlias(context)
        if (context.packageManager.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            context.packageManager.setComponentEnabledSetting(
                alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun resolvedHome(context: Context) = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY
    )

    private fun isDefault(context: Context): Boolean =
        resolvedHome(context)?.activityInfo?.packageName == context.packageName

    private fun holdsHomeRole(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    fun status(context: Context): String {
        val resolved = resolvedHome(context)
        val info = resolved?.activityInfo
        if (info?.packageName == context.packageName) {
            return HomeAssistSetup.status(context) + "\n" + "Hệ thống đang chọn TV Dashboard để mở Home. Hãy thử nút Home, khởi động lại và bật từ chế độ chờ."
        }
        val target = info?.let { ComponentName(it.packageName, it.name).flattenToShortString() }
            ?: "chưa xác định"
        val role = if (holdsHomeRole(context)) {
            "TV Dashboard đã giữ vai trò HOME nhưng CHƯA được hệ thống chọn để mở Home."
        } else {
            "TV Dashboard chưa là Home mặc định."
        }
        return HomeAssistSetup.status(context) + "\n" + "$role\nHome đang được chọn: $target (priority=${resolved?.priority ?: 0}).\n" +
                "Mở cài đặt Home để kiểm tra. Launcher hệ thống hoặc chính sách thiết bị có thể ưu tiên ứng dụng khác."
    }

    fun request(activity: Activity) {
        if (!isTv(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("Dùng Dashboard làm Home trên thiết bị này?")
                .setMessage("Khi chọn Dashboard, nút Home của Android sẽ mở dashboard. Bạn có thể đổi lại ở Cài đặt hệ thống. Nút Home riêng của BlueStacks có thể hoạt động khác.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Tiếp tục") { _, _ -> requestSystemHome(activity) }
                .show()
        } else requestSystemHome(activity)
    }

    private fun requestSystemHome(activity: Activity) {
        registerCandidate(activity)
        // Role ownership alone is insufficient: verify the actual HOME resolver first.
        if (isDefault(activity)) {
            Toast.makeText(activity, "Hệ thống đang chọn TV Dashboard để mở Home.", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(activity,
                        "Đã có vai trò HOME nhưng hệ thống vẫn chọn launcher khác. Hãy kiểm tra cài đặt Home.",
                        Toast.LENGTH_LONG).show()
                    openSettings(activity)
                    return
                }
                try {
                    activity.startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                } catch (_: Exception) { /* Fall back to vendor settings without forcing a choice. */ }
            }
        }
        openSettings(activity)
    }

    /** User-facing setup actions: never change another launcher's enabled state. */
    fun openSettings(activity: Activity) {
        registerCandidate(activity)
        val options = arrayOf(
            "Mở trang chọn ứng dụng Home",
            "Mở danh sách ứng dụng mặc định",
            "Mở Cài đặt hệ thống",
            "Kiểm tra trạng thái Home",
            "Thử mở Home",
            "Hỗ trợ tương thích Home (tùy chọn)"
        )
        AlertDialog.Builder(activity)
            .setTitle("Thiết lập Home trên TV")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchSettings(activity, Settings.ACTION_HOME_SETTINGS)
                    1 -> launchSettings(activity, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    2 -> launchSettings(activity, Settings.ACTION_SETTINGS)
                    3 -> showStatus(activity)
                    4 -> testHome(activity)
                    5 -> HomeAssistSetup.show(activity)
                }
            }
            .setNegativeButton("Đóng", null)
            .setNeutralButton("Hướng dẫn") { _, _ ->
                AlertDialog.Builder(activity)
                    .setTitle("Chọn Home bằng điều khiển TV")
                    .setMessage("1. Mở trang chọn Home và chọn TV Dashboard Home. Nếu hệ thống hỏi, chọn Luôn luôn.\n\n" +
                        "2. Nếu không có mục Home, thử Ứng dụng mặc định trong Cài đặt hệ thống. Vị trí phụ thuộc phiên bản TV.\n\n" +
                        "3. Quay lại app để kiểm tra trạng thái, sau đó bấm nút Home thật trên remote.\n\n" +
                        "4. Thử riêng khởi động lại và bật từ chế độ chờ.\n\n" +
                        "Nếu TV Xiaomi không cung cấp lựa chọn launcher, hãy xem Hỗ trợ tương thích Home (tùy chọn). Cơ chế hỗ trợ chuyển hướng khác với Home mặc định và không bảo đảm trên mọi firmware.")
                    .setPositiveButton("Đã hiểu", null)
                    .show()
            }
            .show()
    }

    private fun launchSettings(activity: Activity, action: String) {
        try {
            activity.startActivity(Intent(action))
        } catch (_: android.content.ActivityNotFoundException) {
            unavailable(activity)
        } catch (_: SecurityException) {
            unavailable(activity)
        }
    }

    private fun unavailable(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("TV không hỗ trợ mở trang này")
            .setMessage("Hãy thử mục khác trong Thiết lập Home. Nếu Cài đặt hệ thống không có ứng dụng Home mặc định, TV có thể giới hạn đổi launcher; đây không phải quyền app có thể tự cấp.")
            .setPositiveButton("Quay lại") { _, _ -> openSettings(activity) }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showStatus(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Trạng thái Home hiện tại")
            .setMessage(status(activity))
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun testHome(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Thử mở Home hệ thống?")
            .setMessage("App sẽ gửi yêu cầu Home thông thường. Nếu mở launcher khác, hãy mở lại TV Dashboard từ danh sách ứng dụng. Sau đó vẫn cần thử nút Home thật trên remote vì firmware có thể xử lý nút này khác.")
            .setPositiveButton("Thử Home") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: android.content.ActivityNotFoundException) {
                    unavailable(activity)
                } catch (_: SecurityException) {
                    unavailable(activity)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
