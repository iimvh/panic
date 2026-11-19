import { hero, stats } from '../data/content.js'

export default function Hero() {
  return (
    <section id="top" className="relative isolate overflow-hidden">
      {/* Ảnh nền */}
      <img
        src={hero.image}
        alt="Đồi cà phê xanh tại Đắk Lắk"
        className="absolute inset-0 -z-20 size-full object-cover"
        fetchPriority="high"
      />
      <div
        className="absolute inset-0 -z-10 bg-gradient-to-b from-espresso-950/80 via-espresso-950/60 to-espresso-950/90"
        aria-hidden="true"
      />

      <div className="container-page flex min-h-[92svh] flex-col justify-center pt-28 pb-16 sm:pt-32">
        <div className="max-w-3xl">
          <span className="inline-flex items-center gap-2 rounded-full border border-honey-300/40 bg-espresso-950/30 px-4 py-1.5 text-xs font-semibold uppercase tracking-[0.2em] text-honey-300 backdrop-blur">
            <span className="size-1.5 rounded-full bg-honey-400" aria-hidden="true" />
            {hero.eyebrow}
          </span>

          <h1 className="mt-6 font-display text-4xl leading-[1.1] text-espresso-50 sm:text-5xl md:text-6xl">
            {hero.title}
          </h1>

          <p className="mt-6 max-w-xl text-base leading-relaxed text-espresso-100 sm:text-lg">
            {hero.description}
          </p>

          <div className="mt-9 flex flex-col gap-3 sm:flex-row sm:items-center">
            <a href={hero.primaryCta.href} className="btn-accent">
              {hero.primaryCta.label}
              <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="2.2">
                <path d="M5 12h14M13 6l6 6-6 6" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </a>
            <a
              href={hero.secondaryCta.href}
              className="btn border border-espresso-50/40 text-espresso-50 hover:bg-espresso-50/10"
            >
              {hero.secondaryCta.label}
            </a>
          </div>
        </div>

        {/* Số liệu nông trại */}
        <dl className="mt-14 grid grid-cols-2 gap-x-6 gap-y-8 border-t border-espresso-50/20 pt-8 sm:mt-20 md:grid-cols-4">
          {stats.map((item) => (
            <div key={item.label}>
              <dt className="sr-only">{item.label}</dt>
              <dd>
                <span className="block font-display text-3xl text-honey-300 sm:text-4xl">{item.value}</span>
                <span className="mt-1 block text-xs uppercase tracking-[0.14em] text-espresso-200 sm:text-sm sm:tracking-normal sm:normal-case">
                  {item.label}
                </span>
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  )
}
