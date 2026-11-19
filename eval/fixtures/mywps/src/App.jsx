import { useEffect, useState } from 'react'
import Header from './components/Header.jsx'
import Hero from './components/Hero.jsx'
import About from './components/About.jsx'
import Products from './components/Products.jsx'
import Process from './components/Process.jsx'
import Tours from './components/Tours.jsx'
import Gallery from './components/Gallery.jsx'
import Testimonials from './components/Testimonials.jsx'
import BookingForm from './components/BookingForm.jsx'
import Footer from './components/Footer.jsx'
import { formatPrice } from './components/Section.jsx'

export default function App() {
  // Giỏ hàng đơn giản: chỉ đếm số gói đã thêm, chưa có thanh toán.
  const [cart, setCart] = useState([])
  const [toast, setToast] = useState(null)

  const addToCart = (product) => {
    setCart((prev) => [...prev, product])
    setToast(`${product.name} — ${formatPrice(product.price)}`)
  }

  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(null), 2600)
    return () => window.clearTimeout(timer)
  }, [toast])

  return (
    <>
      <a
        href="#gioi-thieu"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-[60] focus:rounded-full focus:bg-espresso-900 focus:px-5 focus:py-3 focus:text-sm focus:text-espresso-50"
      >
        Bỏ qua điều hướng
      </a>

      <Header />

      <main>
        <Hero />
        <About />
        <Products onAdd={addToCart} />
        <Process />
        <Tours />
        <Gallery />
        <Testimonials />
        <BookingForm />
      </main>

      <Footer />

      {/* Thông báo thêm vào giỏ */}
      <div
        className={`fixed bottom-5 left-1/2 z-50 -translate-x-1/2 transition-all duration-300 ${
          toast ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-4 opacity-0'
        }`}
        role="status"
        aria-live="polite"
      >
        <div className="flex items-center gap-3 rounded-full bg-espresso-900 px-5 py-3 text-sm text-espresso-50 shadow-xl">
          <span className="grid size-6 place-items-center rounded-full bg-leaf-500 text-white" aria-hidden="true">
            <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="3">
              <path d="M20 6L9 17l-5-5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
          <span>Đã thêm: {toast}</span>
          <span className="rounded-full bg-espresso-800 px-2 py-0.5 text-xs">{cart.length}</span>
        </div>
      </div>
    </>
  )
}
