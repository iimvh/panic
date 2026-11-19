import { useEffect, useState } from 'react'
import { navLinks, site } from '../data/content.js'

export default function Header() {
  const [scrolled, setScrolled] = useState(false)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  // Khoá cuộn nền khi mở menu mobile
  useEffect(() => {
    document.body.style.overflow = open ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  return (
    <header
      className={`fixed inset-x-0 top-0 z-50 transition-all duration-300 ${
        scrolled || open
          ? 'bg-espresso-50/95 shadow-sm shadow-espresso-900/10 backdrop-blur'
          : 'bg-transparent'
      }`}
    >
      <div className="container-page flex h-16 items-center justify-between gap-4 sm:h-20">
        <a
          href="#top"
          className="flex items-center gap-2.5"
          onClick={() => setOpen(false)}
          aria-label={`${site.fullName} — về đầu trang`}
        >
          <span
            className={`grid size-9 place-items-center rounded-full font-display text-lg font-semibold transition-colors ${
              scrolled || open
                ? 'bg-espresso-800 text-espresso-50'
                : 'bg-espresso-50/90 text-espresso-900'
            }`}
            aria-hidden="true"
          >
            E
          </span>
          <span className="leading-tight">
            <span
              className={`block font-display text-lg font-semibold ${
                scrolled || open ? 'text-espresso-900' : 'text-espresso-50'
              }`}
            >
              {site.name}
            </span>
            <span
              className={`block text-[0.65rem] uppercase tracking-[0.18em] ${
                scrolled || open ? 'text-leaf-600' : 'text-espresso-100/80'
              }`}
            >
              Coffee Farm
            </span>
          </span>
        </a>

        <nav className="hidden items-center gap-7 lg:flex" aria-label="Điều hướng chính">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className={`text-sm font-medium transition-colors hover:text-honey-500 ${
                scrolled ? 'text-espresso-700' : 'text-espresso-50'
              }`}
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="flex items-center gap-2">
          <a href="#dat-tour" className="btn-accent hidden sm:inline-flex">
            Đặt tour
          </a>
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className={`grid size-10 place-items-center rounded-full border transition-colors lg:hidden ${
              scrolled || open
                ? 'border-espresso-300 text-espresso-800'
                : 'border-espresso-50/40 text-espresso-50'
            }`}
            aria-expanded={open}
            aria-controls="mobile-menu"
            aria-label={open ? 'Đóng menu' : 'Mở menu'}
          >
            <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2">
              {open ? (
                <path d="M6 6l12 12M18 6L6 18" strokeLinecap="round" />
              ) : (
                <path d="M4 7h16M4 12h16M4 17h16" strokeLinecap="round" />
              )}
            </svg>
          </button>
        </div>
      </div>

      {/* Menu mobile */}
      <div
        id="mobile-menu"
        className={`overflow-hidden border-t border-espresso-200 bg-espresso-50 transition-[max-height] duration-300 lg:hidden ${
          open ? 'max-h-[26rem]' : 'max-h-0'
        }`}
      >
        <nav className="container-page flex flex-col gap-1 py-4" aria-label="Điều hướng di động">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              onClick={() => setOpen(false)}
              className="rounded-lg px-3 py-3 text-base font-medium text-espresso-800 hover:bg-espresso-100"
            >
              {link.label}
            </a>
          ))}
          <a href="#dat-tour" onClick={() => setOpen(false)} className="btn-accent mt-2">
            Đặt tour trải nghiệm
          </a>
        </nav>
      </div>
    </header>
  )
}
