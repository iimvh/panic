// Kiểm tra tạm: hành vi form đặt tour (validate + thông báo thành công)
// và sự hiện diện của các breakpoint responsive trong CSS đã build.
import { JSDOM } from 'jsdom'
import { createServer } from 'vite'
import { readFileSync, readdirSync } from 'node:fs'

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

const vite = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
const { createElement } = await import('react')
const { createRoot } = await import('react-dom/client')
const { act } = await import('react')
const BookingForm = (await vite.ssrLoadModule('/src/components/BookingForm.jsx')).default

const root = createRoot(document.getElementById('root'))
await act(async () => root.render(createElement(BookingForm)))

const results = []
const check = (label, ok) => results.push([label, Boolean(ok)])

const form = document.querySelector('#dat-tour form')
check('Form render', form !== null)

// 1) Gửi form trống -> phải báo lỗi, không hiện thông báo thành công
await act(async () => {
  form.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true }))
})
const errText = document.body.textContent
check('Form trống báo lỗi họ tên', errText.includes('Vui lòng nhập họ tên'))
check('Form trống báo lỗi SĐT', errText.includes('Số điện thoại chưa hợp lệ'))
check('Form trống báo lỗi ngày', errText.includes('Chọn ngày bạn muốn đến'))
check('Form trống KHÔNG báo thành công', !errText.includes('Chúng tôi đã nhận yêu cầu'))

// 2) Điền hợp lệ -> hiện thông báo thành công
const setValue = (el, value) => {
  const proto = el.tagName === 'TEXTAREA' ? dom.window.HTMLTextAreaElement.prototype : dom.window.HTMLInputElement.prototype
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set
  setter.call(el, value)
  el.dispatchEvent(new dom.window.Event('input', { bubbles: true }))
}

await act(async () => {
  setValue(document.getElementById('name'), 'Nguyễn Văn A')
  setValue(document.getElementById('phone'), '0901234567')
  setValue(document.getElementById('date'), '2025-12-20')
})
await act(async () => {
  document.querySelector('#dat-tour form').dispatchEvent(
    new dom.window.Event('submit', { bubbles: true, cancelable: true }),
  )
})
const okText = document.body.textContent
check('Form hợp lệ hiện thông báo thành công', okText.includes('Chúng tôi đã nhận yêu cầu đặt tour'))
check('Thông báo nhắc lại tên khách', okText.includes('Cảm ơn Nguyễn Văn A'))
check('Có nút gửi yêu cầu khác', okText.includes('Gửi yêu cầu khác'))

// 3) CSS build có breakpoint responsive + theme màu
const assets = readdirSync('dist/assets')
const cssFile = assets.find((f) => f.endsWith('.css'))
const css = readFileSync(`dist/assets/${cssFile}`, 'utf8')
check('CSS build tồn tại', Boolean(cssFile))
// Tailwind v4 phát ra breakpoint dạng rem: 40rem = 640px, 48rem = 768px, 64rem = 1024px
check('Có media query sm (640px / 40rem)', /min-width:\s*(640px|40rem)/.test(css))
check('Có media query md (768px / 48rem)', /min-width:\s*(768px|48rem)/.test(css))
check('Có media query lg (1024px / 64rem)', /min-width:\s*(1024px|64rem)/.test(css))
check('Có theme màu espresso', /--color-espresso-\d+/.test(css))
check('Có theme màu leaf', /--color-leaf-\d+/.test(css))
check('Có theme màu honey', /--color-honey-\d+/.test(css))
check('Có font Playfair Display', css.includes('Playfair Display'))
check('Có animation reveal', css.includes('.reveal'))

let failed = 0
for (const [label, ok] of results) {
  if (!ok) failed++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}`)
}
console.log(failed === 0 ? '\nTẤT CẢ KIỂM TRA ĐẠT' : `\n${failed} kiểm tra lỗi`)
await vite.close()
process.exit(failed === 0 ? 0 : 1)
