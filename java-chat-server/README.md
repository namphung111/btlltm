# Realtime Chat (Java Socket)

Ứng dụng chat theo mô hình client–server. Máy chạy `ChatServer` là server; các máy chạy `ChatClient` kết nối đến server qua TCP port `9001`.

## Yêu cầu

- JDK 17 trở lên
- Mạng LAN/Wi-Fi giữa máy server và client nếu chạy khác máy

## Biên dịch

Mở PowerShell tại thư mục `java-chat-server`:

```powershell
javac -d out (Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

## Chạy server

Trên máy server:

```powershell
java -cp out com.chat.server.ChatServer
```

Mặc định server lắng nghe trên port `9001`. Có thể chọn port khác bằng:

```powershell
java -Dserver.port=9002 -cp out com.chat.server.ChatServer
```

Server phải tiếp tục chạy trong cửa sổ terminal. Nếu client chạy ở máy khác, mở Windows Defender Firewall cho kết nối TCP đến port server, ví dụ:

```powershell
New-NetFirewallRule -DisplayName "Realtime Chat TCP 9001" -Direction Inbound -Protocol TCP -LocalPort 9001 -Action Allow
```

Chỉ tạo quy tắc firewall khi bạn có quyền quản trị máy và muốn cho phép client trong mạng kết nối.

Tìm IPv4 của server bằng `ipconfig`; địa chỉ thường có dạng `192.168.x.x` hoặc `10.x.x.x`. Client phải dùng địa chỉ LAN này, không dùng `127.0.0.1` nếu chạy trên máy khác.

## Dữ liệu mẫu

Khi khởi động, server tự nạp các tài khoản mẫu (cùng mật khẩu):

| Username | Password |
|---|---|
| `alice` | `ChatDemo123!` |
| `bob` | `ChatDemo123!` |
| `charlie` | `ChatDemo123!` |

Hai phòng mẫu đã có tin nhắn lịch sử:

| Tên phòng | ID phòng | Thành viên |
|---|---|---|
| Phòng chung | `demo-room-general` | alice, bob, charlie |
| Nhóm bài tập | `demo-room-team` | alice, bob |

Dùng một tài khoản ở trên để đăng nhập client, nhập ID phòng để tham gia và xem lịch sử mẫu. Server cũng in tài khoản và ID phòng demo ra terminal khi khởi động. Các tài khoản do người dùng tự đăng ký trong lúc chạy cũng chỉ tồn tại trong bộ nhớ; khi server tắt, chúng sẽ bị xóa và dữ liệu mẫu được nạp lại ở lần khởi động sau. Không dùng mật khẩu demo này cho hệ thống thật.

## Chạy client Swing

Trên máy server hoặc máy client đã biên dịch project:

```powershell
java -cp out com.chat.server.ChatClient
```

Client cũng nhận host và port qua tham số:

```powershell
java -cp out com.chat.server.ChatClient 192.168.1.10 9001
```

Thay `192.168.1.10` bằng IPv4 thực của máy server. Trong màn hình đăng nhập:

1. Chọn **Đăng ký**, nhập username và password.
2. Đăng nhập bằng tài khoản vừa tạo.
3. Tạo phòng chat rồi gửi ID phòng cho người khác, hoặc nhập ID phòng để tham gia.
4. Gửi tin nhắn hoặc file trong phòng đang mở.

## Chức năng client

- Đăng ký, đăng nhập
- Xem danh sách người dùng online và làm mới danh sách
- Tạo phòng, tham gia phòng bằng ID và tải lịch sử phòng
- Gửi/nhận tin nhắn realtime
- Gửi file tối đa 5 MB; client nhận file có thể chọn nơi lưu

## Giao thức và giới hạn hiện tại

- Server nhận Java `ObjectStream` trên TCP; đây không phải HTTP và không thể kiểm tra bằng cách mở port trong trình duyệt.
- Dữ liệu người dùng, phòng và lịch sử hiện nằm trong bộ nhớ server, sẽ mất khi server khởi động lại; dữ liệu mẫu sẽ được nạp lại.
- Thành viên phòng và file được truyền qua server hiện chưa có lưu trữ bền vững; file được gửi dưới dạng dữ liệu kèm tin nhắn.
- Đây là bản bài tập/demo: không mở port ra Internet công cộng. Trước khi triển khai thực tế cần bổ sung database, mã hóa kết nối, giới hạn/kiểm tra dữ liệu đầu vào và xác thực quyền truy cập phòng.
