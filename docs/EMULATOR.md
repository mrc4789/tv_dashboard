# Quét QR từ điện thoại khi app chạy trên Android Emulator

## Cách dùng

1. Mở project đã sửa trong Android Studio, Sync và Run bản **debug** lên emulator.
2. Máy tính và điện thoại kết nối cùng Wi-Fi. Trong Windows, đặt mạng Wi-Fi tin cậy này thành **Private**.
3. Nhấp đúp `tools/Start-Phone-Config.cmd`, chấp nhận quyền Administrator. Không phải tự nhập lệnh.
4. Đóng và mở lại màn hình **Cài đặt** trong TvDashboard để server đọc cấu hình mới. QR và dòng địa chỉ sẽ cùng dùng IP LAN máy tính, ví dụ `http://192.168.1.105:8086/`.
5. Quét QR mới, nhập PIN trên màn hình TV để gửi URL dashboard.

Khi đổi Wi-Fi: chỉ nhấp đúp lại file CMD rồi đóng/mở Cài đặt. IP được tự phát hiện, không cần sửa code hoặc build lại.
Sau khi khởi động lại emulator/ADB/máy tính cũng chạy lại file CMD.

## Chỉ một chỗ cấu hình

`tools/LanSettings.ps1` chứa toàn bộ IP, cổng và ADB:

- `PcLanIp = ""`: tự tìm IP Wi-Fi/Ethernet của máy tính. Nếu có nhiều card mạng, nhập IP đúng tại đây.
- `DeviceSerial = ""`: tự chọn nếu có một thiết bị ADB; nếu có nhiều thiết bị, nhập serial tại đây.
- `AdbPath = ""`: tìm ADB trong PATH hoặc `%LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe`.
- `PhonePort = 8086`: cổng điện thoại truy cập trên máy tính.
- `AdbPort = 8085`: cổng loopback ADB trên máy tính.
- `ServerPort = 8080`: cổng server trong emulator. Trong chế độ bridge dùng cổng cố định này, không tự chuyển cổng để tránh lệch ADB forward.

Nếu Windows báo cổng bị chiếm/dành riêng, sửa cổng tương ứng **chỉ tại LanSettings.ps1** rồi chạy lại CMD. Script gửi cùng cấu hình sang app; QR không có IP/cổng máy tính viết cứng trong Kotlin.
Nếu cấu hình thất bại, khắc phục lỗi in màu đỏ rồi chạy lại. Khi setup đã chạy nhưng trang chưa mở được, giữ màn hình Cài đặt mở; thử địa chỉ READY trên máy tính và kiểm tra Wi-Fi không bật client/AP isolation (mạng khách thường chặn).

## Thiết bị thật

Android TV/TV Box thật không cần script hay máy tính trung gian. App tự lấy IPv4 của mạng đang dùng. Sau khi đổi Wi-Fi, mở lại Cài đặt để tạo QR theo IP mới. Bản release luôn bỏ qua cấu hình bridge debug.
Nếu dùng cùng bản debug trên thiết bị thật sau khi đã bật bridge, xóa dữ liệu app hoặc chạy script Stop để bỏ cấu hình bridge.

## Gỡ cấu hình thử nghiệm

Mở PowerShell Administrator, chạy `tools/Stop-Phone-Config.ps1` (nếu Windows chặn script, dùng `powershell -ExecutionPolicy Bypass -File tools/Stop-Phone-Config.ps1`). Chỉ xóa proxy/firewall riêng của TvDashboard và file cấu hình debug; mở lại Cài đặt.

## Thay đổi mã nguồn

- `DebugLanConfig.kt`: đọc cấu hình bridge private của app, chỉ ở bản debug; kiểm tra IPv4 LAN và cổng hợp lệ.
- `RemoteConfigController.kt`: dùng URL bridge cho QR khi có cấu hình, dùng IP thiết bị khi không có; giữ đúng cổng bridge.
- `ConfigServer.kt`: kiểm tra địa chỉ client qua `session.remoteIpAddress`.
- `QrCodeGenerator.kt` không cần sửa: vẫn mã hóa đúng chuỗi URL từ controller; dòng địa chỉ và QR cùng lấy `Info.address`.

Chưa chạy build Android hoặc thử mạng Windows trong môi trường chỉnh sửa này vì không có Android SDK/Gradle wrapper và máy Windows/emulator. Cần Sync, Run và kiểm tra trên thiết bị của bạn.
