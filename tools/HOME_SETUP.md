# Setup Home cho TV Dashboard

## Bản app dùng để triển khai
Dùng APK `release` tiêu chuẩn từ project đã có bản vá launcher. Không chọn homeAssistRelease. Nếu chuyển từ bản hỗ trợ, tắt hỗ trợ/trợ năng trước, rồi cài đè APK cùng khóa ký. Không xóa dữ liệu hoặc gỡ app: giữ Device ID, API key và PIN.
Gói này chỉ bổ sung công cụ Windows và tài liệu; không thay Kotlin, manifest, API, dashboard hay log. Không cần build lại APK nếu release tiêu chuẩn hiện tại đã có HomeLauncher.

## Chuẩn bị trên máy tính
- Python 3 có Tkinter (bản cài Python cho Windows thường có tùy chọn Tcl/Tk). Công cụ không tự tải/cài dependencies.
- Android SDK Platform-Tools: chọn adb.exe đã có trong Android Studio SDK hoặc bộ Platform-Tools chính thức. Không cần tài khoản administrator.
- Máy tính và TV chung mạng nếu dùng ADB qua mạng. Đây là công cụ lắp đặt tại chỗ, không mở ADB ra internet.

## Thao tác
1. Copy `Setup-TV-Home.cmd` và thư mục `tools` vào đúng project.
2. Cài APK release lên TV, mở một lần.
3. Trên TV, bật Developer options và debugging mà model hỗ trợ; xác nhận kết nối máy tính trên màn hình TV.
4. Double-click `Setup-TV-Home.cmd`. Nếu chưa thấy đường dẫn adb.exe, bấm Chọn file.
5. Emulator đang mở: bấm Làm mới thiết bị. TV qua mạng: nhập IPv4:cổng debugging do TV cung cấp rồi bấm Kết nối TV.
6. Nếu TV chỉ cung cấp Wireless debugging ghép bằng mã, công cụ hiện chưa có luồng pair; cần ghép trước bằng công cụ Android Studio tương thích. Không mặc định mọi Xiaomi đều dùng cổng 5555.
7. Chọn đúng thiết bị có trạng thái device. Offline: chờ khởi động, kiểm tra kết nối. Unauthorized: xác nhận trên TV. Không Wipe Data.
8. Bấm Kiểm tra Home, rồi Đặt Dashboard làm Home. Công cụ xác định user hiện tại, không mặc định user 0.
9. Đọc kết quả: chỉ coi thành công khi Home resolver thực tế trả về Dashboard. Dòng Success của ADB riêng lẻ không được coi là thành công.
10. Bấm Thử Home và thử nút Home thật trên remote.

## Khi vẫn về Google/Xiaomi Home
Đây là bước nâng cao dành cho kỹ thuật viên, KHÔNG tự chạy:
- Bấm Nâng cao: tắt launcher Home cũ, kiểm tra đúng thiết bị, user và component hiển thị trong xác nhận.
- Chỉ đồng ý nếu có thể giữ debugging để khôi phục. Tắt launcher hãng có thể làm mất giao diện Home và chức năng liên quan của hãng.
- Công cụ kiểm tra lại Home chưa thay đổi, kiểm tra đó là ứng viên HOME; chặn Settings/FallbackHome/Dashboard.
- Công cụ kiểm tra mở Dashboard, lưu bản khôi phục rồi mới tắt đúng package Home cũ. Không tắt các package khác như setup wizard.
- Nếu đổi Home không thành công, công cụ cố khôi phục. Nếu khôi phục thất bại, giữ kết nối, không reboot trước khi xử lý; dùng nút Khôi phục Home cũ.
- Không bảo đảm mọi firmware chấp nhận; không có quyền root hay cơ chế bypass Play Protect.

## Khôi phục
Bấm Khôi phục Home cũ cho đúng thiết bị/user. Nếu đã tắt package, công cụ bật lại rồi khôi phục lựa chọn Home và xác minh. Bản lưu chỉ xóa khi khôi phục đã được xác minh.
Bản lưu nằm trong `%USERPROFILE%\.tvdashboard-home-backups`, gắn với serial ADB và user. Nếu địa chỉ IP/cổng của TV thay đổi thì serial mạng cũng đổi: giữ nguyên bản lưu và nhờ kỹ thuật viên đối chiếu thiết bị trước khi khôi phục. Không tự áp bản lưu của TV khác.
Không xóa thư mục này trước khi nghiệm thu. Cài lại Windows/đổi máy tính không mang theo bản lưu tự động.

## Nghiệm thu trước bàn giao
Ghi kết quả trong HOME_COMPATIBILITY.csv. Kiểm tra nút Home, reboot, standby/wake, mất điện (nếu được phép), mất mạng/kết nối lại, PIN/log còn nguyên. Thử mở Cài đặt và khôi phục Home cũ.
Chỉ tắt debugging/ngắt máy tính sau khi tất cả tình huống cần thiết đã ổn; việc cập nhật firmware/reset TV có thể làm phải setup lại.

## Kiểm thử đã chạy
- 17 unittest với ADB giả lập phản hồi, kiểm tra kết quả và chuỗi thao tác.
- Python syntax compile; import Tkinter thành công.
- Chưa chạy GUI trên Windows hoặc kiểm tra giao diện trực quan (môi trường không có display/Xvfb).
- Không có adb/thiết bị kết nối; chưa chạy lệnh trên emulator/TV Xiaomi thật.
- Không build APK ở gói này.
Chạy lại test: `python -m unittest discover -s tools/tests -p test_home_setup.py -v`.
