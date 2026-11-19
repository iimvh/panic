// Kiểm tra tạm: mọi module dev server trả về 200 và không lỗi biên dịch.
const files = [
  'src/main.jsx', 'src/App.jsx', 'src/index.css', 'src/data/content.js',
  'src/components/Section.jsx', 'src/components/Header.jsx', 'src/components/Hero.jsx',
  'src/components/About.jsx', 'src/components/Products.jsx', 'src/components/Process.jsx',
  'src/components/Tours.jsx', 'src/components/Gallery.jsx', 'src/components/Testimonials.jsx',
  'src/components/BookingForm.jsx', 'src/components/Footer.jsx',
]

const base = 'http://localhost:5199/'
let bad = 0
for (const f of files) {
  const res = await fetch(base + f)
  const body = await res.text()
  const err = /Internal server error|Failed to resolve import|Transform failed|SyntaxError/i.test(body)
  if (!res.ok || err) bad++
  console.log(`${res.ok && !err ? 'OK  ' : 'FAIL'} ${res.status} ${f}`)
  if (err) console.log(body.slice(0, 400))
}
console.log(bad === 0 ? '\nTất cả module biên dịch OK' : `\n${bad} module lỗi`)
process.exit(bad === 0 ? 0 : 1)
