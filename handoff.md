Nguyên nhân gốc (đã xác nhận bằng số liệu)

- Cơ chế xoá tool result cũ ước lượng kích thước trên turn chưa xoá. Một khi vượt 16K, nó xoá mọi kết quả trừ 3 cái mới nhất, ở mọi step sau đó.
- Agent cần khoảng 7 file cùng lúc để viết code, nên mỗi lần đọc file này thì file kia bị xoá, và nó cứ đọc lại vòng tròn.
- Baseline tái hiện ổn định: t1 (task blog) fail 3/3 lần, mỗi lần chạm trần 150 step, đọc 236–288 lần, 0 dòng code. t4 (đọc repo lớn) có lần chạm trần 300 step.

Đã làm

- Sửa lỗi gốc: chỉ xoá khi request thực gửi vượt 75% ngân sách, xoá vừa đủ, và kết quả đã xoá thì giữ nguyên trạng thái xoá để prefix ổn định cho cache.
- Ngoài mức các harness khác:
  - Xoá theo giá trị: bản đọc của file đã bị sửa (stale) và bản đọc trùng bị xoá trước.
  - Đọc lại một file chưa đổi mà nội dung vẫn còn trong context thì chỉ nhận một dòng trỏ về lần đọc trước.
  - File vừa bị xoá mà model đọc lại ngay thì được ghim, để bị xoá sau cùng.
  - Khi xoá một file, giữ lại outline (danh sách khai báo kèm số dòng) thay vì một dòng placeholder rỗng.
- Bộ dừng stalled: sau 15 lời gọi liên tiếp không có thông tin mới thì nhắc model; sau 30 thì dừng turn.
- Tool mới: grep/glob viết bằng Java thuần (không cần ripgrep, chạy được trên Windows); read_file mặc định đọc tối đa 2000 dòng.
- Đo lường:
  - cachedTokens trong trace.
  - Bộ mô phỏng E1 chạy trong gradlew check.
  - Bộ eval E2 trong eval/: 4 task, fixture, metrics, report.
- Test hồi quy tái hiện đúng vòng 7 file của đêm hôm qua. Toàn bộ test Java và UI pass.

Số liệu đã có (mô phỏng E1)

- Khi dùng đúng tỉ lệ token/ký tự của đêm đó với ngân sách 32K:
  - Bản cũ, bản chỉ sửa lỗi gốc, bản thêm resources và bản thêm pinning đều lặp tới trần 300 step.
  - Chỉ bản có outline hoàn thành, sau 21 step.
- Khi mọi thứ vừa ngân sách (128K), tất cả các bản đều đọc mỗi file đúng một lần.

Còn tồn đọng

- Chưa có A/B trên model thật. Đây là bằng chứng quyết định, đang chờ chạy.
- Pinning và resources không cho thấy tác dụng trong mô phỏng. Nếu eval thật cũng vậy, nên bỏ hai cơ chế này theo nguyên tắc chỉ giữ thứ đã chứng minh được.
- Turn dài vẫn phình ra. Kể cả khi đã xoá hết, placeholder và các message của assistant vẫn tích tụ, nên request có thể vượt ngân sách. Muốn giải quyết tận gốc cần tóm tắt giữa turn (Panic hiện chỉ tóm tắt giữa các turn).
- Chưa commit gì.

Issue mới phát hiện

- Prompt tiếng Việt qua tham số dòng lệnh trên Windows bị thành ?, vì Java giải mã theo code page ANSI. Eval đã tránh bằng cách gửi qua stdin, nhưng lỗi vẫn còn trong Panic.
- Nhà cung cấp river không ổn định: bị giới hạn tốc độ (429) và ngắt stream giữa chừng. Eval tự chạy lại những lần này và không tính là lỗi của agent.
- Lệnh exec trên Windows chạy qua cmd, nên model hay gõ cú pháp bash và bị lỗi, tốn step.
- Một test của :trace fail một lần rồi pass khi chạy lại, nhiều khả năng là test chập chờn trên Windows (không liên quan đến thay đổi này).
- Nếu chạy Panic bên trong repo này, nó đọc luôn AGENTS.md/CLAUDE.md của repo. Eval đã chuyển sang chạy ở thư mục tạm bên ngoài.