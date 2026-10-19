# Realtime Chat (Java Socket + MySQL)

Ứng dụng chat desktop Swing theo mô hình client–server. Client kết nối TCP tới server; MySQL lưu tài khoản, phòng chat, thành viên phòng, tin nhắn và dữ liệu file. Danh sách online được giữ trong bộ nhớ khi server đang chạy.

## Yêu cầu

- JDK 17 trở lên
- Maven 3.9 trở lên
- MySQL 8.0 trở lên
- Mạng LAN/Wi-Fi giữa máy server và client nếu chạy khác máy

## Thiết kế database

File schema: [`database/schema.sql`](./database/schema.sql). Schema tạo database `chat_app` và các bảng:

| Bảng | Mục đích | Quan hệ chính |
|---|---|---|
| `users` | Tài khoản và password hash | Một người dùng có thể tạo phòng, tham gia phòng và gửi tin nhắn |
| `rooms` | Thông tin phòng và người tạo | Liên kết tới `users` qua `created_by` |
| `room_members` | Danh sách thành viên phòng | Bảng liên kết nhiều-nhiều giữa `rooms` và `users` |
| `messages` | Tin nhắn, thời gian, file đính kèm | Liên kết tới phòng và người gửi; file lưu dưới dạng BLOB |

Các khóa ngoại giữ dữ liệu thành viên và tin nhắn nhất quán; chỉ mục trên thành viên và lịch sử tin nhắn hỗ trợ truy vấn theo phòng. Username được so sánh phân biệt chữ hoa/thường. Ứng dụng tự tạo các bảng còn thiếu khi khởi động; database phải được tạo trước.

## Import database và dữ liệu mẫu bằng XAMPP

1. Mở **XAMPP Control Panel**, nhấn **Start** ở hàng **MySQL**.
2. Mở [http://localhost/phpmyadmin](http://localhost/phpmyadmin).
3. Chọn tab **Import**. Ở **Choose File**, chọn file `database/schema.sql` trong thư mục dự án (đường dẫn hiện tại: `C:\namtu\LTM\btl\java-chat-server\database\schema.sql`).
4. Giữ định dạng **SQL**, chọn encoding `utf-8` nếu phpMyAdmin hỏi, rồi nhấn **Import** ở cuối trang.
5. Mở database `chat_app` trong danh sách bên trái để xem các bảng `users`, `rooms`, `room_members`, `messages`.

File SQL tạo database/bảng và nạp tài khoản, phòng, thành viên cùng lịch sử chat mẫu. Có thể import lại: dữ liệu đã tồn tại không bị ghi đè và tin nhắn mẫu không bị nhân đôi. Trên database mới, mật khẩu của cả ba tài khoản mẫu là `ChatDemo123!`; nếu username mẫu đã có từ trước thì file import giữ nguyên mật khẩu hiện tại của tài khoản đó.

Nếu phpMyAdmin không cho phép tạo database, hãy tạo database `chat_app` trước trong giao diện **Databases**, rồi import lại file SQL. Không xóa database đang có dữ liệu cần giữ.

## Tạo database và tài khoản MySQL (tùy chọn)

Thay cho phpMyAdmin, có thể nạp schema bằng MySQL command line:

```powershell
Get-Content .\database\schema.sql | mysql -u root -p
```

Tạo tài khoản riêng cho ứng dụng trong MySQL, thay mật khẩu ví dụ bằng mật khẩu mạnh của bạn:

```sql
CREATE USER 'chat_app'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, REFERENCES, INDEX
    ON chat_app.* TO 'chat_app'@'localhost';
```

Nếu chạy MySQL trên máy khác, hãy thay `localhost` trong tài khoản MySQL và cấu hình `CHAT_DB_URL` bên dưới bằng hostname/IP của máy database. Chỉ cho phép kết nối từ các máy cần thiết; không mở MySQL trực tiếp ra Internet công cộng.

## Cấu hình kết nối

Server mặc định dùng URL `jdbc:mysql://localhost:3306/chat_app?serverTimezone=UTC`. Cấu hình bằng biến môi trường:

```powershell
$env:CHAT_DB_URL = "jdbc:mysql://localhost:3306/chat_app?serverTimezone=UTC"
$env:CHAT_DB_USER = "root"
$env:CHAT_DB_PASSWORD = ""
```

XAMPP thường cấu hình MySQL `root` không có mật khẩu trong môi trường local mới cài; hãy dùng đúng thông tin ở mục **User accounts** của phpMyAdmin nếu bạn đã đổi. Nếu đã tạo user riêng `chat_app` theo mục trên, hãy thay `CHAT_DB_USER` và `CHAT_DB_PASSWORD` bằng user/mật khẩu đó.

Hoặc dùng Java system properties `chat.db.url`, `chat.db.user`, `chat.db.password` khi khởi chạy server. Không lưu mật khẩu thật vào source code hoặc commit vào Git. Driver MySQL Connector/J được khai báo trong `pom.xml`.

## Biên dịch và chạy server

Tại thư mục `java-chat-server`:

```powershell
mvn clean package
mvn dependency:copy-dependencies
java -cp "target\classes;target\dependency\*" com.chat.server.ChatServer
```

Server mặc định lắng nghe TCP port `9001`. Có thể đổi port:

```powershell
java -Dserver.port=9002 -cp "target\classes;target\dependency\*" com.chat.server.ChatServer
```

Nếu MySQL chưa chạy, chưa có database, hoặc thông tin đăng nhập sai, server sẽ dừng và in lỗi kết nối ra terminal. Server tự tạo bảng còn thiếu và nạp tài khoản/phòng demo một lần; dữ liệu demo không ghi đè tài khoản hay phòng đã tồn tại.

Để kết nối client từ máy khác, tìm IPv4 máy server bằng `ipconfig`. Chỉ mở Windows Defender Firewall cho kết nối TCP tới port server nếu bạn có quyền quản trị và cần cho phép client trong mạng:

```powershell
New-NetFirewallRule -DisplayName "Realtime Chat TCP 9001" -Direction Inbound -Protocol TCP -LocalPort 9001 -Action Allow
```

## Chạy client

Trên máy server hoặc máy client đã cài JDK, từ thư mục `java-chat-server`:

```powershell
java -cp "target\classes;target\dependency\*" com.chat.server.ChatClient
```

Khi chạy client ở máy khác, truyền IPv4 và port của server:

```powershell
java -cp "target\classes;target\dependency\*" com.chat.server.ChatClient 192.168.1.10 9001
```

Trong cửa sổ client:

1. Chọn **Đăng ký** để tạo tài khoản hoặc dùng tài khoản demo.
2. Tạo phòng mới hoặc nhập ID phòng được chia sẻ để tham gia.
3. Gửi tin nhắn hoặc file (tối đa 5 MB); người nhận có thể chọn nơi lưu file.
4. Chọn **Đăng xuất** khi kết thúc phiên.

## Dữ liệu demo

Server tạo các tài khoản mẫu nếu chúng chưa tồn tại:

| Username | Password |
|---|---|
| `alice` | `ChatDemo123!` |
| `bob` | `ChatDemo123!` |
| `charlie` | `ChatDemo123!` |

Hai phòng mẫu có lịch sử chat:

| Tên phòng | ID phòng | Thành viên |
|---|---|---|
| Phòng chung | `demo-room-general` | alice, bob, charlie |
| Nhóm bài tập | `demo-room-team` | alice, bob |

Không dùng tài khoản/mật khẩu demo trong môi trường thực tế.

## Lưu trữ và giới hạn

- Tài khoản, phòng, thành viên, lịch sử tin nhắn và file được lưu trong MySQL, nên vẫn còn sau khi server khởi động lại.
- Trạng thái online chỉ có ý nghĩa trong phiên chạy hiện tại của server.
- Mật khẩu hiện được băm SHA-256 theo cách của ứng dụng demo; trước khi triển khai thực tế cần thay bằng thuật toán chuyên dụng có salt như Argon2id hoặc bcrypt.
- Giao thức là Java `ObjectStream` trên TCP, không phải HTTP. Đây là ứng dụng bài tập/demo: không mở port ra Internet công cộng; cần bổ sung TLS, hardening xác thực và kiểm tra đầu vào trước khi triển khai thực tế.
