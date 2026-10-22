# Realtime Chat — Spring Boot, WebSocket, JavaFX, PostgreSQL

Đây là phiên bản server trung tâm của ứng dụng chat. Các client chỉ cần biết địa chỉ server; họ không kết nối trực tiếp với nhau. Server Spring Boot cung cấp REST API, WebSocket `/ws` và lưu tài khoản, phòng, lịch sử chat, biên nhận đã xem trong PostgreSQL.

## Yêu cầu

- JDK 17 trở lên
- Maven 3.9 trở lên
- Docker Desktop đang chạy để chạy PostgreSQL local theo cấu hình mẫu (hoặc một PostgreSQL 14+ có sẵn)

## Chạy demo trên Windows

### 1. Cài và khởi động Docker Desktop

Máy cần cài Docker Desktop cho Windows, bật backend WSL 2 nếu được hỏi, rồi mở Docker Desktop và đợi trạng thái báo engine đã chạy. Nếu lệnh `docker` chưa được nhận diện, hãy cài Docker Desktop hoặc đóng/mở lại PowerShell sau khi cài.

Kiểm tra trong PowerShell:

```powershell
docker --version
docker compose version
```

Nếu Docker Desktop báo `DockerDesktop/Wsl/ExecError` hoặc WSL báo không tìm thấy đường dẫn, mở **PowerShell as Administrator** và chạy:

```powershell
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
```

Khởi động lại Windows, mở PowerShell thường và chạy:

```powershell
wsl --update
wsl --status
```

Mở lại Docker Desktop, đợi Docker Engine khởi động xong rồi kiểm tra lại `docker compose version`.

### 2. Chạy PostgreSQL và backend

Mở PowerShell ở thư mục gốc repository (`C:\namtu\LTM\btl`) như sau:

```powershell
Set-Location .\chat-platform
docker compose up -d postgres
```

Giữ thư mục làm việc là `chat-platform`. Ở cửa sổ PowerShell thứ nhất, chạy backend:

```powershell
$env:CHAT_DB_URL = "jdbc:postgresql://localhost:5432/chat_app"
$env:CHAT_DB_USER = "chat_app"
$env:CHAT_DB_PASSWORD = "chat_demo_password"
$env:CHAT_DEMO_DATA = "true"
mvn -f server\pom.xml spring-boot:run
```

Nếu vẫn đứng ở thư mục gốc repository, dùng đường dẫn đầy đủ tương đối:

```powershell
mvn -f chat-platform\server\pom.xml spring-boot:run
```

Giữ cửa sổ này mở trong khi sử dụng ứng dụng. Nếu thấy `Port 8080 was already in use`, backend đang chạy ở một cửa sổ khác; dùng backend đó hoặc nhấn `Ctrl+C` trong cửa sổ backend cũ rồi khởi động lại. Không thể chạy đồng thời hai backend trên cùng cổng `8080`. Nếu không tìm được cửa sổ đang giữ cổng, kiểm tra tiến trình trước khi dừng:

```powershell
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -First 1
Get-Process -Id $listener.OwningProcess
```

Chỉ dừng tiến trình sau khi xác nhận đó là backend của ứng dụng này.

Nếu chat riêng báo `POST /api/direct-conversations/... (HTTP 404)`, hãy kiểm tra URL server trên màn hình đăng nhập là `http://localhost:8080` và backend đang chạy từ module `chat-platform/server`. Backend TCP/Swing cũ trong `java-chat-server` không cung cấp API này. Sau khi đổi mã backend, dừng tiến trình backend cũ rồi khởi động lại để nạp phiên bản mới.

Để tạo JAR server thay vì chạy Maven:

```powershell
mvn -f server\pom.xml clean package
java -jar server\target\chat-platform-server-1.0.0.jar
```

Trong cửa sổ PowerShell thứ hai, cũng chuyển vào thư mục `chat-platform` trước khi khởi chạy client:

```powershell
Set-Location 'C:\namtu\LTM\btl\chat-platform'
mvn -f client\pom.xml javafx:run
```

Để mở thêm client trên cùng máy, mở cửa sổ PowerShell khác, chuyển vào `chat-platform` và chạy lại lệnh trên. Mỗi cửa sổ client có thể đăng nhập bằng một tài khoản khác nhau để thử chat realtime. Ở màn hình đăng nhập, địa chỉ server cho máy này là `http://localhost:8080`.

Ở màn hình đăng nhập, nhập `http://localhost:8080`. Có thể đăng ký tài khoản mới hoặc dùng dữ liệu demo:

| Tên đăng nhập | Mật khẩu |
|---|---|
| `alice` | `ChatDemo123!` |
| `bob` | `ChatDemo123!` |
| `charlie` | `ChatDemo123!` |

Với dữ liệu demo, phòng chung có ID `demo-room-general`; nhóm bài tập có ID `demo-room-team`. Có thể tạo phòng mới và chia sẻ ID phòng cho người khác.

`CHAT_DEMO_DATA=true` chỉ thêm tài khoản/phòng mẫu còn thiếu, không đổi mật khẩu những tài khoản đã có. Hãy tắt cờ này khi chạy môi trường thực tế. Cấu hình Docker Compose mẫu chỉ bind PostgreSQL vào loopback trên máy cục bộ; mật khẩu ở đây chỉ dành cho demo.

Database PostgreSQL của phiên bản này tách biệt với MySQL/XAMPP của bản cũ; ứng dụng không tự chuyển tài khoản, lịch sử chat hay file cũ sang database mới. Bật dữ liệu demo sẽ tạo dữ liệu mẫu mới trong PostgreSQL.

## Tính năng

- Tài khoản đăng ký/đăng nhập với mật khẩu băm BCrypt và token bearer hết hạn sau 24 giờ.
- Đổi ảnh đại diện bằng nút **Đổi avatar** (PNG, JPEG, GIF hoặc BMP, tối đa 5 MB); avatar người đã xem hiển thị dưới tin nhắn của bạn.
- Tạo nhóm và thêm thành viên bằng username; người tạo tự động là trưởng nhóm. Danh sách thành viên hiển thị vai trò và trạng thái online/offline realtime.
- Thành viên có thể rời nhóm; trưởng nhóm phải chọn người kế nhiệm trước khi rời. Trưởng nhóm có thể chuyển quyền riêng hoặc xóa mềm nhóm; lịch sử tin nhắn được giữ lại nhưng nhóm đã xóa không còn truy cập được.
- Nhấn avatar thành viên trong phòng để mở **Trò chuyện riêng**; hội thoại 1-1 được lưu độc lập, không tạo phòng.
- Chat riêng hỗ trợ tin nhắn văn bản, lịch sử và trạng thái đã xem; gửi tệp hiện chỉ khả dụng trong phòng chat.
- Nhắn tin realtime qua WebSocket; lịch sử gần nhất (tối đa 200 tin) lưu trong PostgreSQL.
- Gửi ảnh/tệp tối đa 10 MB, xem trước ảnh trong client và tải tệp xuống. Nội dung file được lưu trong thư mục `uploads` của server; PostgreSQL lưu metadata.
- Lưu và phát trạng thái đã xem theo từng tin nhắn.
- Hỗ trợ nhiều phiên kết nối cho cùng tài khoản. Token lưu trong bộ nhớ server nên người dùng phải đăng nhập lại sau khi server khởi động lại.

## Kết nối qua Internet

Địa chỉ `localhost` chỉ dùng khi client chạy trên cùng máy server. Để người ở mạng khác kết nối:

1. Triển khai backend và PostgreSQL trên VPS hoặc máy chủ có thể truy cập ổn định. Bảo vệ PostgreSQL ở private network, không mở cổng `5432` ra Internet.
2. Đặt reverse proxy như Caddy/Nginx trước backend, bật HTTPS và hỗ trợ WebSocket upgrade cho đường dẫn `/ws`.
3. Mở từ Internet duy nhất cổng HTTPS `443` (và `80` nếu cần cấp chứng chỉ tự động). Không dùng HTTP/`ws://` cho thông tin đăng nhập hoặc token ở môi trường thực.
4. Tạo tài khoản PostgreSQL riêng, đổi mật khẩu demo, đặt `CHAT_DB_URL`, `CHAT_DB_USER`, `CHAT_DB_PASSWORD` và `CHAT_UPLOAD_DIR` trên máy server.
5. Chạy client ở mọi máy và nhập `https://ten-mien-cua-ban` ở màn hình đăng nhập. Client tự đổi sang `wss://.../ws`.

Chưa có VPS/tên miền nào được cấu hình bởi project này; ví dụ URL chỉ có thể hoạt động sau khi bạn triển khai server thật. Nếu dùng tunnel, máy chủ của bạn và tunnel cần luôn chạy.

## Giao thức

- `POST /api/auth/register`, `POST /api/auth/login`: tạo tài khoản/đăng nhập, trả token. `POST /api/auth/logout` thu hồi token hiện tại.
- `GET /api/users?q=...`: tìm danh bạ theo username hoặc tên hiển thị (tối đa 50 kết quả), kèm trạng thái online.
- `PATCH /api/profile` với `{"displayName":"Tên hiển thị"}` cập nhật tên hiển thị; `PATCH /api/profile/password` với `{"currentPassword":"...","newPassword":"..."}` đổi mật khẩu. Hai API cần bearer token.
- `POST /api/profile/avatar` (multipart field `file`) cập nhật ảnh đại diện tài khoản; `GET /api/users/{username}/avatar` trả ảnh đại diện. Cả hai yêu cầu bearer token.
- `POST /api/direct-conversations/{username}` mở hoặc lấy hội thoại riêng duy nhất với người dùng; `GET /api/direct-conversations` liệt kê hội thoại; `GET /api/direct-conversations/{conversationId}/messages` tải tối đa 200 tin gần nhất.
- `POST /api/rooms` với `{"name":"Tên nhóm","members":["alice","bob"]}` tạo nhóm, người gửi thành trưởng nhóm; `members` có thể bỏ qua hoặc để rỗng.
- `POST /api/rooms/{roomId}/join` tham gia bằng ID; `GET /api/rooms/{roomId}/members` trả danh sách thành viên và vai trò.
- `POST /api/rooms/{roomId}/leave` cho thành viên rời nhóm. Trưởng nhóm cần gửi `{"successorUsername":"alice"}` để chuyển quyền và rời nhóm trong cùng transaction.
- `POST /api/rooms/{roomId}/transfer-leadership` với `{"successorUsername":"alice"}` chuyển quyền trưởng nhóm; `DELETE /api/rooms/{roomId}` xóa mềm nhóm. Hai thao tác chỉ trưởng nhóm hiện tại được phép thực hiện.
- REST `/api/rooms...` và `/api/files/{fileId}` yêu cầu `Authorization: Bearer <token>`.
- WebSocket `/ws` cũng yêu cầu bearer token trong header bắt tay.
- Sự kiện WebSocket cho phòng: `subscribe`, `message`, `read`; cho chat riêng: `direct_subscribe`, `direct_message`, `direct_read`. Tin nhắn riêng được phát tới các phiên của hai người tham gia, kể cả khi người nhận chưa mở cuộc trò chuyện.
- WebSocket phát `group_created`, `group_member_left`, `group_leader_changed` và `group_deleted` để cập nhật danh sách nhóm/thành viên theo thời gian thực.

Schema được khởi tạo tự động từ `server/src/main/resources/schema.sql`. Đường dẫn upload mặc định là `uploads`; cấu hình bằng biến môi trường `CHAT_UPLOAD_DIR`. Giới hạn file có thể điều chỉnh tại cấu hình backend, nhưng client hiện chỉ dùng luồng tải file đơn giản cho giới hạn 10 MB.

## Cấu trúc

- `server/` — backend Spring Boot, PostgreSQL, API và WebSocket.
- `client/` — ứng dụng desktop JavaFX.
- `docker-compose.yml` — PostgreSQL local dành cho phát triển/demo.
- `../java-chat-server/` — phiên bản TCP/Swing cũ được giữ nguyên, không dùng bởi phiên bản này.
