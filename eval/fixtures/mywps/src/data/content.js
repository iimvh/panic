/**
 * ============================================================
 *  NỘI DUNG TRANG — SỬA MỌI THỨ Ở ĐÂY
 * ============================================================
 *  Toàn bộ chữ, giá, ảnh của landing page nằm trong file này.
 *  Muốn đổi nội dung thật của nông trại: chỉ cần sửa các object
 *  bên dưới, không phải động vào component.
 *
 *  Ảnh: đang dùng URL Unsplash (ảnh mẫu). Thay bằng ảnh thật bằng
 *  cách bỏ file vào thư mục `public/images/` rồi đổi đường dẫn
 *  thành "/images/ten-anh.jpg".
 * ============================================================
 */

export const site = {
  name: 'Ea Kao',
  fullName: 'Nông Trại Cà Phê Ea Kao',
  tagline: 'Cà phê Đắk Lắk nguyên bản',
  phone: '0262 385 1975',
  phoneHref: 'tel:+842623851975',
  email: 'hello@eakaocoffee.vn',
  address: 'Thôn 7, xã Ea Kao, TP. Buôn Ma Thuột, Đắk Lắk',
  hours: 'Thứ 2 – Thứ 7: 7:00 – 17:30 · Chủ nhật: 8:00 – 16:00',
  mapUrl: 'https://maps.google.com/?q=Buon+Ma+Thuot+Dak+Lak',
  socials: [
    { label: 'Facebook', href: 'https://facebook.com' },
    { label: 'Instagram', href: 'https://instagram.com' },
    { label: 'YouTube', href: 'https://youtube.com' },
  ],
}

export const navLinks = [
  { label: 'Nông trại', href: '#gioi-thieu' },
  { label: 'Cà phê', href: '#san-pham' },
  { label: 'Quy trình', href: '#quy-trinh' },
  { label: 'Trải nghiệm', href: '#trai-nghiem' },
  { label: 'Thư viện', href: '#thu-vien' },
  { label: 'Liên hệ', href: '#dat-tour' },
]

export const hero = {
  eyebrow: 'Buôn Ma Thuột · Đắk Lắk',
  title: 'Từ đồi cà phê Đắk Lắk đến tách cà phê của bạn',
  description:
    'Chúng tôi trồng, hái và rang cà phê ngay tại nông trại trên cao nguyên đất đỏ. Mời bạn mua cà phê rang mộc hoặc đến ở lại một đêm giữa rẫy cà phê đang mùa chín.',
  image:
    'https://images.unsplash.com/photo-1447933601403-0c6688de566e?auto=format&fit=crop&w=1920&q=80',
  primaryCta: { label: 'Mua cà phê', href: '#san-pham' },
  secondaryCta: { label: 'Đặt tour trải nghiệm', href: '#trai-nghiem' },
}

export const stats = [
  { value: '32 ha', label: 'Diện tích nông trại' },
  { value: '1998', label: 'Năm bắt đầu trồng' },
  { value: '60 tấn', label: 'Sản lượng mỗi năm' },
  { value: '100%', label: 'Rang mộc tại chỗ' },
]

export const about = {
  eyebrow: 'Về nông trại',
  title: 'Ba thế hệ giữ một vườn cà phê',
  paragraphs: [
    'Nông trại Ea Kao nằm trên vùng đất bazan đỏ của Buôn Ma Thuột — nơi được xem là thủ phủ cà phê của Việt Nam. Ông nội chúng tôi bắt đầu trồng những gốc Robusta đầu tiên năm 1998, khi cả vùng còn là đồi hoang.',
    'Ngày nay, 32 hecta vườn được chăm theo hướng hữu cơ: bóng mát từ cây muồng đen, phân ủ từ vỏ cà phê, và nước tưới tiết kiệm vào đúng giai đoạn cây cần. Chúng tôi hái chọn từng quả chín, sơ chế ngay trong ngày để giữ vị ngọt tự nhiên.',
    'Mỗi năm, nông trại mở cửa đón khách vào mùa thu hoạch (tháng 11 – tháng 1) để cùng hái cà phê, phơi trên sân gạch và tự tay rang một mẻ nhỏ mang về.',
  ],
  image:
    'https://images.unsplash.com/photo-1524350876685-274059332603?auto=format&fit=crop&w=1200&q=80',
  imageCaption: 'Rẫy cà phê mùa chín, tháng 12',
  highlights: [
    'Chứng nhận hữu cơ theo tiêu chuẩn Việt Nam',
    'Hái chọn tay 100%, không hái tuốt cành',
    'Sơ chế ướt và honey ngay tại nông trại',
    'Bao tiêu thụ ổn định cho 40 hộ liên kết',
  ],
}

export const products = [
  {
    id: 'robusta',
    name: 'Robusta Cổ Điển',
    roast: 'Rang vừa',
    weight: '500g',
    price: 185000,
    note: 'Đậm, hậu vị chocolate đen và hạt dẻ. Phù hợp pha phin truyền thống.',
    badge: 'Bán chạy',
    image:
      'https://images.unsplash.com/photo-1559056199-641a0ac8b55e?auto=format&fit=crop&w=800&q=80',
  },
  {
    id: 'arabica',
    name: 'Arabica Cầu Đất',
    roast: 'Rang nhạt',
    weight: '250g',
    price: 245000,
    note: 'Chua thanh, hương hoa nhài và cam chín. Dành cho pour-over và cold brew.',
    badge: null,
    image:
      'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=800&q=80',
  },
  {
    id: 'honey',
    name: 'Honey Process',
    roast: 'Rang vừa',
    weight: '250g',
    price: 275000,
    note: 'Sơ chế honey giữ lớp mật, vị ngọt mật ong và mận chín, hậu vị dài.',
    badge: 'Mới',
    image:
      'https://images.unsplash.com/photo-1442512595331-e89e73853f31?auto=format&fit=crop&w=800&q=80',
  },
  {
    id: 'combo',
    name: 'Combo 3 Vị Thử',
    roast: '3 gói 200g',
    weight: '600g',
    price: 495000,
    note: 'Ba vị Robusta, Arabica và Honey trong hộp quà giấy kraft — món quà dễ tặng.',
    badge: 'Tiết kiệm 12%',
    image:
      'https://images.unsplash.com/photo-1514432324607-a09d9b4aefdd?auto=format&fit=crop&w=800&q=80',
  },
]

export const process = [
  {
    step: '01',
    title: 'Hái chọn tay',
    text: 'Chỉ hái quả chín đỏ, hái từng đợt cách nhau 10–14 ngày để giữ độ chín đều.',
  },
  {
    step: '02',
    title: 'Sơ chế trong ngày',
    text: 'Quả được xát vỏ và lên men có kiểm soát ngay trong 8 giờ sau khi hái.',
  },
  {
    step: '03',
    title: 'Phơi trên sân gạch',
    text: 'Phơi nắng tự nhiên 12–18 ngày, đảo đều tay, đạt độ ẩm 12% mới đóng bao.',
  },
  {
    step: '04',
    title: 'Rang theo đơn',
    text: 'Rang mẻ nhỏ mỗi tuần, đóng gói có van một chiều và ghi ngày rang trên bao bì.',
  },
]

export const tours = [
  {
    id: 'nua-ngay',
    name: 'Tour Nửa Ngày',
    duration: '4 giờ · 8:00 – 12:00',
    price: 350000,
    unit: '/ khách',
    popular: false,
    includes: [
      'Đi bộ tham quan vườn cà phê cùng nông dân',
      'Thử nếm 3 mẫu cà phê tại xưởng rang',
      'Tự tay rang 100g cà phê mang về',
      'Nước uống và trái cây trong vườn',
    ],
  },
  {
    id: 'mot-ngay',
    name: 'Tour Một Ngày',
    duration: '8 giờ · 8:00 – 16:00',
    price: 690000,
    unit: '/ khách',
    popular: true,
    includes: [
      'Toàn bộ nội dung tour nửa ngày',
      'Trải nghiệm hái cà phê và xát vỏ (mùa thu hoạch)',
      'Bữa trưa nông trại với cá suối và rau vườn',
      'Một gói cà phê 250g và tạp dề lưu niệm',
    ],
  },
  {
    id: 'homestay',
    name: 'Homestay 2N1Đ',
    duration: '2 ngày 1 đêm',
    price: 1450000,
    unit: '/ khách',
    popular: false,
    includes: [
      'Phòng gỗ nhìn ra đồi cà phê, có ban công',
      'Toàn bộ hoạt động tour một ngày',
      'Bữa tối BBQ và ngắm sao trên sân thượng',
      'Cà phê sáng pha phin cùng chủ nông trại',
    ],
  },
]

export const gallery = [
  {
    src: 'https://images.unsplash.com/photo-1509042239860-f550ce710b93?auto=format&fit=crop&w=800&q=80',
    alt: 'Tách cà phê phin trên bàn gỗ',
  },
  {
    src: 'https://images.unsplash.com/photo-1497935586351-b67a49e012bf?auto=format&fit=crop&w=800&q=80',
    alt: 'Những hạt cà phê rang vừa chín',
  },
  {
    src: 'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?auto=format&fit=crop&w=800&q=80',
    alt: 'Quầy pha chế trong xưởng rang',
  },
  {
    src: 'https://images.unsplash.com/photo-1521017432531-fbd92d768814?auto=format&fit=crop&w=800&q=80',
    alt: 'Không gian quán cà phê mộc',
  },
  {
    src: 'https://images.unsplash.com/photo-1461023058943-07fcbe16d735?auto=format&fit=crop&w=800&q=80',
    alt: 'Ly cà phê sữa đá',
  },
  {
    src: 'https://images.unsplash.com/photo-1442550528053-c431ecb55509?auto=format&fit=crop&w=800&q=80',
    alt: 'Đồi cà phê xanh mùa mưa',
  },
]

export const testimonials = [
  {
    name: 'Nguyễn Thu Hà',
    role: 'Khách tour một ngày · Hà Nội',
    quote:
      'Được tự tay hái quả cà phê chín rồi ngồi nghe chú chủ vườn kể chuyện trồng cà phê. Buổi trưa ăn cơm nông trại mà nhớ mãi.',
    rating: 5,
  },
  {
    name: 'Trần Minh Quân',
    role: 'Khách mua cà phê · TP.HCM',
    quote:
      'Gói Robusta rang vừa pha phin ra đúng vị đậm mình thích, lại có ghi ngày rang rõ ràng. Đặt online 2 ngày là nhận được.',
    rating: 5,
  },
  {
    name: 'Lê Phương Anh',
    role: 'Homestay 2N1Đ · Đà Nẵng',
    quote:
      'Sáng mở cửa phòng là thấy sương trên đồi cà phê. Tối ngồi BBQ ngắm sao, yên tĩnh đến mức nghe được tiếng dế.',
    rating: 5,
  },
]

export const booking = {
  eyebrow: 'Đặt tour & liên hệ',
  title: 'Đến nông trại cùng chúng tôi',
  description:
    'Điền thông tin bên dưới, chúng tôi sẽ gọi lại xác nhận trong vòng 24 giờ. Mùa thu hoạch (tháng 11 – tháng 1) nên đặt trước 1–2 tuần.',
  tourOptions: tours.map((t) => ({ value: t.id, label: `${t.name} — ${t.duration}` })),
  note: 'Form mẫu: dữ liệu chưa gửi đi đâu cả. Xem README để nối với email/Google Form/API thật.',
}
