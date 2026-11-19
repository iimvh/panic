# Nông Trại Cà Phê Ea Kao — Landing page

Landing page một trang cho một nông trại cà phê ở Đắk Lắk, phục vụ **hai mục tiêu**:

1. **Bán cà phê** — danh sách sản phẩm, giá, nút "Thêm vào giỏ".
2. **Mời khách trải nghiệm nông trại** — các gói tour, homestay và form đặt tour.

Xây bằng **React + Vite + Tailwind CSS v4**, nội dung tiếng Việt, responsive mobile-first.

---

## Chạy dự án

```bash
npm install      # cài dependencies (lần đầu)
npm run dev      # chạy dev server, mặc định http://localhost:5173
npm run build    # build production vào thư mục dist/
npm run preview  # xem thử bản build
```

Yêu cầu: Node.js 18+ và npm.

---

## Cấu trúc thư mục

```
mywps/
├── index.html              # <head>: title, meta description, Google Fonts
├── vite.config.js          # plugin React + Tailwind v4
├── package.json
├── public/
│   └── images/             # (tạo thêm) đặt ảnh thật ở đây
└── src/
    ├── main.jsx            # điểm vào React
    ├── index.css           # theme màu, font, class dùng chung (.btn, .card, .reveal…)
    ├── App.jsx             # ghép tất cả section + giỏ hàng đơn giản
    ├── data/
    │   └── content.js      # ★ TOÀN BỘ NỘI DUNG NẰM Ở ĐÂY
    └── components/
        ├── Section.jsx     # helper: Reveal (hiệu ứng scroll), SectionHeading, formatPrice
        ├── Header.jsx      # menu sticky, nút "Đặt tour"
        ├── Hero.jsx        # ảnh lớn + 2 CTA
        ├── About.jsx       # câu chuyện + số liệu
        ├── Products.jsx    # card sản phẩm + giá + "Thêm vào giỏ"
        ├── Process.jsx     # timeline 4 bước
        ├── Tours.jsx       # 3 gói trải nghiệm + giá
        ├── Gallery.jsx     # lưới ảnh
        ├── Testimonials.jsx# đánh giá khách
        ├── BookingForm.jsx # form đặt tour (validate phía client)
        └── Footer.jsx      # địa chỉ, giờ mở cửa, mạng xã hội, bản đồ
```

---

## ★ Sửa nội dung ở đâu

**Gần như mọi thứ nằm trong `src/data/content.js`.** Không cần động vào component.

| Muốn đổi | Sửa trong `src/data/content.js` |
|---|---|
| Tên nông trại, hotline, email, địa chỉ, giờ mở cửa, link mạng xã hội | `site` |
| Menu điều hướng trên header | `navLinks` |
| Tiêu đề lớn ở đầu trang, 2 nút CTA | `hero` |
| Số liệu (diện tích, năm thành lập, sản lượng…) | `stats` |
| Câu chuyện nông trại | `about` |
| Sản phẩm: tên, mô tả, **giá**, ảnh, nhãn | `products` |
| Các bước quy trình | `process` |
| Gói tour: tên, thời lượng, giá, điểm nổi bật | `tours` |
| Ảnh thư viện | `gallery` |
| Đánh giá khách hàng | `testimonials` |
| Nhãn/nội dung form đặt tour | `booking` |

Giá hiển thị qua `formatPrice()` trong `src/components/Section.jsx` (định dạng `1.250.000 ₫`).

---

## ★ Thay ảnh thật

Ảnh mẫu đang dùng URL **Unsplash** (ảnh từ xa) để dễ xem trước. Để dùng ảnh thật:

1. Tạo thư mục `public/images/` và bỏ ảnh vào, ví dụ `public/images/vuon-ca-phe.jpg`.
2. Trong `src/data/content.js`, đổi URL thành đường dẫn nội bộ:

```js
// Trước (ảnh mẫu)
image: 'https://images.unsplash.com/photo-...'

// Sau (ảnh thật)
image: '/images/vuon-ca-phe.jpg'
```

Ảnh nên rộng ~1600px, nén dưới ~300KB để tải nhanh.

---

## Đổi màu / font

- **Màu:** bảng màu nâu cà phê (`espresso`), xanh lá (`leaf`), mật ong (`honey`) khai báo trong `@theme` ở `src/index.css`. Đổi mã màu ở đó là toàn trang đổi theo.
- **Font:** Playfair Display (tiêu đề) + Inter (nội dung), nạp qua Google Fonts trong `index.html` và khai báo trong `@theme`.

---

## Ghi chú kỹ thuật

- **Form đặt tour** (`BookingForm.jsx`) hiện chỉ **validate phía client** và hiện thông báo thành công — **chưa gửi đi đâu**. Có đánh dấu `TODO` trong file: cắm API/email service (Formspree, Resend, backend riêng…) tại đó.
- **Giỏ hàng** trong `App.jsx` chỉ đếm số lượng và hiện toast; chưa có thanh toán. Cần tích hợp cổng thanh toán/giỏ hàng thật khi triển khai bán hàng.
- **Hiệu ứng scroll** dùng `IntersectionObserver` (component `Reveal` trong `Section.jsx`), không thêm thư viện animation.
- **Responsive:** mobile-first, breakpoint `sm` / `md` / `lg`; menu mobile có nút mở/đóng riêng.

---

## Kiểm tra đã chạy

- `npm run build` → thành công (41 module, không lỗi).
- `npm run dev` → dev server lên, tất cả module biên dịch không lỗi.
