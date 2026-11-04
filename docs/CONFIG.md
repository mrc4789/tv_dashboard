# Cấu hình URL và xử lý lỗi

## Cách đặt URL dashboard (ưu tiên từ cao xuống thấp)
1. **URL đã lưu trong app** (Cài đặt hoặc adb, xem dưới). Giữ nguyên sau khi khởi động lại.
2. **`DEFAULT_URL`** trong `app/build.gradle.kts` (dùng khi chưa lưu gì).
3. Nếu chưa lưu gì và `DEFAULT_URL` vẫn là `https://example.com` thì app tự mở màn hình Cài đặt ở lần chạy đầu.

### Mở Cài đặt trên TV
- Bấm phím **Menu**, hoặc **giữ OK/Enter 3 giây**.
- Trên emulator: giữ Enter 3 giây (phím Menu thường không có trên bàn phím máy tính).

### Đặt URL qua adb
```
adb shell am start -n com.example.tvdashboard/.ui.MainActivity --es url "https://your-dashboard"
```
URL không hợp lệ (không phải http/https, thiếu host...) sẽ bị từ chối và ghi log.

## HTTP (không phải HTTPS)
- Bản **debug** cho phép HTTP (`app/src/debug/res/xml/network_security_config.xml`).
- Bản **release** chỉ cho HTTPS. Muốn cho phép HTTP, đổi `cleartextTrafficPermitted` thành `true` trong `app/src/main/res/xml/network_security_config.xml`.
- Lỗi chứng chỉ SSL **không bao giờ** bị bỏ qua. Dashboard dùng chứng chỉ tự ký cần được cài chứng chỉ vào thiết bị hoặc đổi sang chứng chỉ hợp lệ.

## Hành vi khi lỗi
| Tình huống | Hành vi |
|---|---|
| Chưa có mạng khi boot | Hiện "Đang chờ kết nối mạng...", có mạng thì tự tải |
| Mất mạng khi đang hiển thị trang | Giữ nguyên trang, có mạng lại thì tải lại |
| Lỗi tải trang chính (DNS, kết nối, HTTP 5xx, SSL) | Hiện lớp phủ lỗi và tự thử lại: 10s, 20s, 40s, ... tối đa 5 phút |
| Tải trang quá 60 giây không xong | Coi như lỗi và thử lại |
| Tiến trình render của WebView bị crash/kill | Dựng lại WebView và tải lại |
| Reload định kỳ | Mặc định 360 phút, đổi trong Cài đặt (0 = tắt) |

Log có tag `MainActivity` và `ReloadManager`:
```
adb logcat -s MainActivity ReloadManager
```

## Chạy unit test
Trong Android Studio, đồng bộ project Gradle rồi chạy task `:app:testDebugUnitTest` từ cửa sổ Gradle. Tạo APK debug bằng `:app:assembleDebug`; APK ở `app/build/outputs/apk/debug/app-debug.apk`. Cài nâng cấp giữ dữ liệu app (bao gồm Device ID) bằng `adb install -r app/build/outputs/apk/debug/app-debug.apk`; không chọn xóa dữ liệu khi cài.

## Device ID, PIN và chẩn đoán từ xa
- Device ID được tạo một lần và lưu trong DataStore của ứng dụng. Đổi tên, phòng ban, Wi-Fi, ghép QR/LAN, khởi động lại và cài APK nâng cấp không tạo ID khác. Gỡ app hoặc xóa dữ liệu app sẽ xóa ID cục bộ; không xóa/gộp bản ghi TV cũ trên server.
- Key vẫn gắn với Device ID và chỉ truy cập được các phòng ban trong phạm vi key. Khi đổi phòng ban trên cùng server/key, app xác thực quyền với API trước khi lưu cấu hình mới; nếu server từ chối, cấu hình cũ được giữ nguyên.
- PIN admin được lưu dưới dạng verifier có salt/hash; PIN rõ không được trả về TV hoặc phát qua SSE. Admin chỉ lưu chính sách; trạng thái “TV đã nhận” chỉ xuất hiện sau khi TV xác nhận phiên bản PIN qua API. Khi offline, PIN hiện hành trên TV giữ nguyên đến lần đồng bộ hợp lệ.
- Admin bật chẩn đoán trên server trước; giao diện phân biệt chính sách server với xác nhận TV. TV ghi nhận lỗi trang chính và sự kiện “WebView báo tải xong”; sự kiện tải xong không xác nhận đăng nhập hoặc dữ liệu nghiệp vụ của website. Lỗi tài nguyên phụ không đánh dấu hỏng toàn trang.
- Log chỉ gồm loại sự kiện, mã số, ID ngẫu nhiên và thời điểm; không chứa URL, nội dung trang, cookie, token, API key hay PIN. TV giữ tối đa 100 sự kiện trong 7 ngày, gửi theo lô khi có mạng; hàng đợi được xóa khi TV nhận chính sách tắt hoặc cấu hình server/key thay đổi để không chuyển log sang server khác. Server lưu tối đa 250 log/TV trong 7 ngày.
