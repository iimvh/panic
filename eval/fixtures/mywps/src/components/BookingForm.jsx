import { useState } from 'react'
import { booking, site } from '../data/content.js'
import { Reveal, SectionHeading } from './Section.jsx'

const initialForm = { name: '', phone: '', email: '', tour: booking.tourOptions[0].value, guests: '2', date: '', message: '' }

export default function BookingForm() {
  const [form, setForm] = useState(initialForm)
  const [errors, setErrors] = useState({})
  const [sent, setSent] = useState(false)

  const update = (field) => (e) => {
    setForm((prev) => ({ ...prev, [field]: e.target.value }))
    setErrors((prev) => ({ ...prev, [field]: undefined }))
  }

  const validate = () => {
    const next = {}
    if (!form.name.trim()) next.name = 'Vui lòng nhập họ tên.'
    if (!/^[0-9+\s.-]{8,15}$/.test(form.phone.trim())) next.phone = 'Số điện thoại chưa hợp lệ.'
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim()))
      next.email = 'Email chưa hợp lệ.'
    if (!form.date) next.date = 'Chọn ngày bạn muốn đến.'
    return next
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    const next = validate()
    setErrors(next)
    if (Object.keys(next).length > 0) return

    // TODO: nối với backend / Google Form / email thật ở đây.
    // Ví dụ: await fetch('/api/booking', { method: 'POST', body: JSON.stringify(form) })
    setSent(true)
  }

  const inputClass = (field) =>
    `w-full rounded-xl border bg-white px-4 py-3 text-sm text-espresso-900 placeholder:text-espresso-400
     focus:outline-none focus:ring-2 focus:ring-honey-400/60 ${
       errors[field] ? 'border-red-400' : 'border-espresso-200 focus:border-espresso-400'
     }`

  return (
    <section id="dat-tour" className="scroll-mt-24 bg-espresso-50 py-20 sm:py-28">
      <div className="container-page grid gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-16">
        <Reveal>
          <SectionHeading
            eyebrow={booking.eyebrow}
            title={booking.title}
            description={booking.description}
          />

          <dl className="mt-8 space-y-5 text-sm">
            <div className="flex gap-3">
              <dt className="sr-only">Địa chỉ</dt>
              <svg viewBox="0 0 24 24" className="mt-0.5 size-5 shrink-0 text-leaf-600" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <path d="M12 21s7-5.6 7-11a7 7 0 10-14 0c0 5.4 7 11 7 11z" strokeLinejoin="round" />
                <circle cx="12" cy="10" r="2.5" />
              </svg>
              <dd className="text-espresso-700">{site.address}</dd>
            </div>
            <div className="flex gap-3">
              <dt className="sr-only">Điện thoại</dt>
              <svg viewBox="0 0 24 24" className="mt-0.5 size-5 shrink-0 text-leaf-600" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <path d="M4 5c0 8 7 15 15 15l2-3-4-2-2 2c-3-1.5-5.5-4-7-7l2-2-2-4-3 1z" strokeLinejoin="round" />
              </svg>
              <dd>
                <a href={site.phoneHref} className="font-semibold text-espresso-900 hover:text-leaf-700">
                  {site.phone}
                </a>
              </dd>
            </div>
            <div className="flex gap-3">
              <dt className="sr-only">Giờ mở cửa</dt>
              <svg viewBox="0 0 24 24" className="mt-0.5 size-5 shrink-0 text-leaf-600" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                <circle cx="12" cy="12" r="9" />
                <path d="M12 7v5l3 2" strokeLinecap="round" />
              </svg>
              <dd className="text-espresso-700">{site.hours}</dd>
            </div>
          </dl>

          <a
            href={site.mapUrl}
            target="_blank"
            rel="noreferrer"
            className="mt-8 inline-flex items-center gap-2 text-sm font-semibold text-leaf-700 underline underline-offset-4 hover:text-leaf-600"
          >
            Xem đường đi trên Google Maps
            <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
              <path d="M7 17L17 7M9 7h8v8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </a>
        </Reveal>

        <Reveal delay={120}>
          <div className="card p-6 sm:p-8">
            {sent ? (
              <div className="flex flex-col items-center py-10 text-center" role="status">
                <span className="grid size-14 place-items-center rounded-full bg-leaf-100 text-leaf-700">
                  <svg viewBox="0 0 24 24" className="size-7" fill="none" stroke="currentColor" strokeWidth="2.4" aria-hidden="true">
                    <path d="M20 6L9 17l-5-5" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </span>
                <h3 className="mt-5 font-display text-2xl text-espresso-900">Cảm ơn {form.name}!</h3>
                <p className="mt-2 max-w-sm text-sm text-espresso-700">
                  Chúng tôi đã nhận yêu cầu đặt tour và sẽ gọi lại số {form.phone} trong 24 giờ.
                </p>
                <button
                  type="button"
                  onClick={() => {
                    setForm(initialForm)
                    setSent(false)
                  }}
                  className="btn-outline mt-6"
                >
                  Gửi yêu cầu khác
                </button>
              </div>
            ) : (
              <form onSubmit={handleSubmit} noValidate className="space-y-4">
                <div className="grid gap-4 sm:grid-cols-2">
                  <div>
                    <label htmlFor="name" className="mb-1.5 block text-sm font-medium text-espresso-800">
                      Họ và tên <span className="text-red-500">*</span>
                    </label>
                    <input
                      id="name"
                      name="name"
                      type="text"
                      autoComplete="name"
                      value={form.name}
                      onChange={update('name')}
                      placeholder="Nguyễn Văn A"
                      className={inputClass('name')}
                      aria-invalid={Boolean(errors.name)}
                    />
                    {errors.name && <p className="mt-1 text-xs text-red-600">{errors.name}</p>}
                  </div>

                  <div>
                    <label htmlFor="phone" className="mb-1.5 block text-sm font-medium text-espresso-800">
                      Số điện thoại <span className="text-red-500">*</span>
                    </label>
                    <input
                      id="phone"
                      name="phone"
                      type="tel"
                      autoComplete="tel"
                      value={form.phone}
                      onChange={update('phone')}
                      placeholder="0901 234 567"
                      className={inputClass('phone')}
                      aria-invalid={Boolean(errors.phone)}
                    />
                    {errors.phone && <p className="mt-1 text-xs text-red-600">{errors.phone}</p>}
                  </div>
                </div>

                <div>
                  <label htmlFor="email" className="mb-1.5 block text-sm font-medium text-espresso-800">
                    Email
                  </label>
                  <input
                    id="email"
                    name="email"
                    type="email"
                    autoComplete="email"
                    value={form.email}
                    onChange={update('email')}
                    placeholder="ban@email.com"
                    className={inputClass('email')}
                    aria-invalid={Boolean(errors.email)}
                  />
                  {errors.email && <p className="mt-1 text-xs text-red-600">{errors.email}</p>}
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div>
                    <label htmlFor="tour" className="mb-1.5 block text-sm font-medium text-espresso-800">
                      Gói trải nghiệm
                    </label>
                    <select
                      id="tour"
                      name="tour"
                      value={form.tour}
                      onChange={update('tour')}
                      className={inputClass('tour')}
                    >
                      {booking.tourOptions.map((opt) => (
                        <option key={opt.value} value={opt.value}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                  </div>

                  <div>
                    <label htmlFor="guests" className="mb-1.5 block text-sm font-medium text-espresso-800">
                      Số khách
                    </label>
                    <input
                      id="guests"
                      name="guests"
                      type="number"
                      min="1"
                      max="12"
                      value={form.guests}
                      onChange={update('guests')}
                      className={inputClass('guests')}
                    />
                  </div>
                </div>

                <div>
                  <label htmlFor="date" className="mb-1.5 block text-sm font-medium text-espresso-800">
                    Ngày dự kiến <span className="text-red-500">*</span>
                  </label>
                  <input
                    id="date"
                    name="date"
                    type="date"
                    value={form.date}
                    onChange={update('date')}
                    className={inputClass('date')}
                    aria-invalid={Boolean(errors.date)}
                  />
                  {errors.date && <p className="mt-1 text-xs text-red-600">{errors.date}</p>}
                </div>

                <div>
                  <label htmlFor="message" className="mb-1.5 block text-sm font-medium text-espresso-800">
                    Lời nhắn
                  </label>
                  <textarea
                    id="message"
                    name="message"
                    rows="3"
                    value={form.message}
                    onChange={update('message')}
                    placeholder="Ví dụ: nhóm 4 người, muốn tham gia hái cà phê..."
                    className={`${inputClass('message')} resize-y`}
                  />
                </div>

                <button type="submit" className="btn-accent w-full">
                  Gửi yêu cầu đặt tour
                </button>
                <p className="text-center text-xs text-espresso-500">{booking.note}</p>
              </form>
            )}
          </div>
        </Reveal>
      </div>
    </section>
  )
}
