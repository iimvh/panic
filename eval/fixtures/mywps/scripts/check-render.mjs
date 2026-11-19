// Kiểm tra tạm: dùng Vite SSR để biên dịch JSX, render App thật trong jsdom,
// rồi xác nhận đủ 10 section, có sản phẩm + giá, có tour + form đặt tour.
import { JSDOM } from 'jsdom'
import { createServer } from 'vite'

const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
  url: 'http://localhost/',
  pretendToBeVisual: true,
})

globalThis.window = dom.window
globalThis.document = dom.window.document
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true })
globalThis.HTMLElement = dom.window.HTMLElement
globalThis.Node = dom.window.Node
globalThis.IntersectionObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'error',
})

const { createElement } = await import('react')
const { createRoot } = await import('react-dom/client')
const { act } = await import('react')
const App = (await vite.ssrLoadModule('/src/App.jsx')).default

const root = createRoot(document.getElementById('root'))
await act(async () => {
  root.render(createElement(App))
})

const html = document.body.innerHTML
const text = document.body.textContent

const checks = [
  ['Header (nav + nút Đặt tour)', () => document.querySelector('header') && text.includes('Đặt tour')],
  ['Hero (#top)', () => document.querySelector('#top') && text.includes('Từ đồi cà phê Đắk Lắk')],
  ['Giới thiệu (#gioi-thieu)', () => document.querySelector('#gioi-thieu') && text.includes('Ba thế hệ')],
  ['Sản phẩm (#san-pham)', () => document.querySelector('#san-pham') && text.includes('Robusta Cổ Điển')],
  ['Giá sản phẩm hiển thị', () => text.includes('185.000') && text.includes('495.000')],
  ['Nút Thêm vào giỏ', () => (html.match(/Thêm vào giỏ/g) || []).length >= 4],
  ['Quy trình (#quy-trinh)', () => document.querySelector('#quy-trinh') && text.includes('Hái chọn tay')],
  ['Trải nghiệm (#trai-nghiem)', () => document.querySelector('#trai-nghiem') && text.includes('Tour Nửa Ngày')],
  ['Giá tour hiển thị', () => text.includes('350.000') && text.includes('1.450.000')],
  ['Thư viện (#thu-vien)', () => document.querySelector('#thu-vien') && document.querySelectorAll('#thu-vien img').length === 6],
  ['Đánh giá khách hàng', () => text.includes('Nguyễn Thu Hà') && text.includes('Trần Minh Quân')],
  ['Form đặt tour (#dat-tour)', () => document.querySelector('#dat-tour form') !== null],
  ['Form có input bắt buộc', () => ['name', 'phone', 'tour', 'guests', 'date', 'message'].every((id) => document.getElementById(id))],
  ['Footer', () => document.querySelector('footer') && text.includes('Bảo lưu mọi quyền')],
  ['Ảnh dùng URL thật', () => (html.match(/images\.unsplash\.com/g) || []).length >= 10],
]

let failed = 0
for (const [label, fn] of checks) {
  let ok = false
  try {
    ok = Boolean(fn())
  } catch {
    ok = false
  }
  if (!ok) failed++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`)
}

const sections = document.querySelectorAll('main > section')
console.log(`\nSố <section> trong <main>: ${sections.length}`)
console.log(failed === 0 ? 'TẤT CẢ KIỂM TRA ĐẠT' : `${failed} kiểm tra lỗi`)

await vite.close()
process.exit(failed === 0 ? 0 : 1)
