# mycordis — các lớp

Xây lại Cordis từ kernel nguyên bản, mỗi lớp giải quyết đúng một vấn đề.
Mục tiêu: hiểu raw logic để tự viết lại bằng bất kỳ ngôn ngữ nào (không dùng Proxy/magic).

| Lớp | Thêm gì | Vấn đề giải quyết | Trạng thái |
|---|---|---|---|
| 0 | `parent`, `disposables`, `effect`, `dispose` | Dọn sạch mọi thứ theo cây | xong (`kernel.mjs`) |
| 1 | `use` | Component lồng component | xong (`kernel.mjs`) |
| 2 | `effect` nhận Promise, iterable, async iterable | Effect bất đồng bộ | chưa |
| 3 | `epoch` | Dispose khi effect async đang chạy dở | chưa |
| 4 | Trạng thái `PENDING → ACTIVE → DISPOSED` | Quan sát được vòng đời | chưa |
| 5 | `Context` (chuỗi `parent`, không dùng prototype) | Mỗi component có phạm vi riêng | chưa |
| 6 | Service qua `get("tên")`, `inject`/`provide` | Component dùng nhau không cần import trực tiếp | chưa |
| 7 | Reload (`_refresh`, `_replace`) | Hot reload khi config hoặc dependency đổi | chưa |

Thứ tự đề xuất: 0 → 1 → 5 → 6 → 2 → 3 → 4 → 7.

## Mô hình lõi

- Temporal: mọi việc đều có cách hoàn tác, dispose theo thứ tự ngược, theo cây (lớp 0–1).
- Spatial: mỗi component có `ctx` riêng kế thừa từ cha, dùng nhau qua service (lớp 5–6).
- `use` không phải cơ chế riêng, chỉ là `effect` đệ quy.

## Đối chiếu với code thật (`packages/core/src`)

- `effect` → `fiber.ts` (`Fiber.effect`, gom disposable, dispose ngược)
- `use` → `fiber.ts:178` (`parent.fiber.effect(..., 'ctx.use()')`) và `registry.ts`
- `DisposableList` → `utils.ts:8`
- Context → `context.ts`; service/Proxy → `reflect.ts` (chưa đọc kỹ)
