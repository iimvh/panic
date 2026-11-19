import { navLinks, site } from '../data/content.js'

export default function Footer() {
  const year = new Date().getFullYear()

  return (
    <footer className="bg-espresso-950 text-espresso-200">
      <div className="container-page grid gap-10 py-14 sm:grid-cols-2 lg:grid-cols-4">
        <div className="sm:col-span-2 lg:col-span-1">
          <div className="flex items-center gap-2.5">
            <span className="grid size-9 place-items-center rounded-full bg-honey-400 font-display text-lg font-semibold text-espresso-950">
              E
            </span>
            <span className="font-display text-lg font-semibold text-espresso-50">{site.fullName}</span>
          </div>
          <p className="mt-4 max-w-xs text-sm leading-relaxed text-espresso-300">
            {site.tagline}. Trồng, sơ chế và rang cà phê ngay trên cao nguyên Buôn Ma Thuột.
          </p>
        </div>

        <div>
          <h3 className="text-xs font-semibold uppercase tracking-[0.18em] text-honey-300">Khám phá</h3>
          <ul className="mt-4 space-y-2.5 text-sm">
            {navLinks.map((link) => (
              <li key={link.href}>
                <a href={link.href} className="text-espresso-300 transition-colors hover:text-espresso-50">
                  {link.label}
                </a>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <h3 className="text-xs font-semibold uppercase tracking-[0.18em] text-honey-300">Liên hệ</h3>
          <ul className="mt-4 space-y-2.5 text-sm text-espresso-300">
            <li>{site.address}</li>
            <li>
              <a href={site.phoneHref} className="transition-colors hover:text-espresso-50">
                {site.phone}
              </a>
            </li>
            <li>
              <a href={`mailto:${site.email}`} className="transition-colors hover:text-espresso-50">
                {site.email}
              </a>
            </li>
            <li className="text-espresso-400">{site.hours}</li>
          </ul>
        </div>

        <div>
          <h3 className="text-xs font-semibold uppercase tracking-[0.18em] text-honey-300">Theo dõi</h3>
          <ul className="mt-4 space-y-2.5 text-sm">
            {site.socials.map((s) => (
              <li key={s.label}>
                <a
                  href={s.href}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 text-espresso-300 transition-colors hover:text-espresso-50"
                >
                  {s.label}
                  <svg viewBox="0 0 24 24" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                    <path d="M7 17L17 7M9 7h8v8" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </a>
              </li>
            ))}
          </ul>
        </div>
      </div>

      <div className="border-t border-espresso-800">
        <div className="container-page flex flex-col gap-2 py-6 text-xs text-espresso-400 sm:flex-row sm:items-center sm:justify-between">
          <p>© {year} {site.fullName}. Bảo lưu mọi quyền.</p>
          <p>Trang mẫu — nội dung và ảnh cần được thay bằng thông tin thật.</p>
        </div>
      </div>
    </footer>
  )
}
