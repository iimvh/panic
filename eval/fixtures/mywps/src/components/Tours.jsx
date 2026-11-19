import { tours } from '../data/content.js'
import { Reveal, SectionHeading, formatPrice } from './Section.jsx'

export default function Tours() {
  return (
    <section id="trai-nghiem" className="scroll-mt-24 bg-espresso-50 py-20 sm:py-28">
      <div className="container-page">
        <Reveal>
          <SectionHeading
            eyebrow="Trải nghiệm nông trại"
            title="Một ngày làm nông dân cà phê"
            description="Tour có hướng dẫn viên là chính người trồng. Nhóm từ 2 khách trở lên, tối đa 12 khách mỗi lượt."
            align="center"
          />
        </Reveal>

        <div className="mt-12 grid gap-6 lg:grid-cols-3">
          {tours.map((tour, i) => (
            <Reveal key={tour.id} delay={i * 100}>
              <article
                className={`relative flex h-full flex-col rounded-2xl border p-6 transition-shadow hover:shadow-lg hover:shadow-espresso-900/10 ${
                  tour.popular
                    ? 'border-honey-400 bg-white shadow-md shadow-honey-600/10 ring-1 ring-honey-400/40'
                    : 'border-espresso-200 bg-white'
                }`}
              >
                {tour.popular && (
                  <span className="absolute -top-3 left-6 rounded-full bg-honey-400 px-3 py-1 text-[0.7rem] font-bold uppercase tracking-wide text-espresso-950">
                    Được chọn nhiều nhất
                  </span>
                )}

                <h3 className="font-display text-2xl text-espresso-900">{tour.name}</h3>
                <p className="mt-1 text-sm font-medium text-leaf-600">{tour.duration}</p>

                <p className="mt-5 flex items-baseline gap-1.5">
                  <span className="font-display text-3xl font-semibold text-espresso-900">
                    {formatPrice(tour.price)}
                  </span>
                  <span className="text-sm text-espresso-500">{tour.unit}</span>
                </p>

                <ul className="mt-6 flex-1 space-y-3 border-t border-espresso-100 pt-5">
                  {tour.includes.map((item) => (
                    <li key={item} className="flex items-start gap-2.5 text-sm text-espresso-700">
                      <svg
                        viewBox="0 0 24 24"
                        className="mt-0.5 size-4 shrink-0 text-leaf-500"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2.5"
                        aria-hidden="true"
                      >
                        <path d="M20 6L9 17l-5-5" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                      <span>{item}</span>
                    </li>
                  ))}
                </ul>

                <a
                  href="#dat-tour"
                  className={`mt-7 ${tour.popular ? 'btn-accent' : 'btn-outline'} w-full`}
                >
                  Đặt tour này
                </a>
              </article>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}
