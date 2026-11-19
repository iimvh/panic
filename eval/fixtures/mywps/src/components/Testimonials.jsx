import { testimonials } from '../data/content.js'
import { Reveal, SectionHeading } from './Section.jsx'

function Stars({ count }) {
  return (
    <div className="flex gap-0.5" aria-label={`${count} trên 5 sao`}>
      {Array.from({ length: 5 }).map((_, i) => (
        <svg
          key={i}
          viewBox="0 0 24 24"
          className={`size-4 ${i < count ? 'text-honey-500' : 'text-espresso-200'}`}
          fill="currentColor"
          aria-hidden="true"
        >
          <path d="M12 2.5l2.9 6.1 6.6.9-4.8 4.6 1.2 6.6L12 17.6 6.1 20.7l1.2-6.6L2.5 9.5l6.6-.9L12 2.5z" />
        </svg>
      ))}
    </div>
  )
}

export default function Testimonials() {
  return (
    <section className="bg-leaf-50 py-20 sm:py-28">
      <div className="container-page">
        <Reveal>
          <SectionHeading
            eyebrow="Khách nói gì"
            title="Những người đã đến và đã uống"
            align="center"
          />
        </Reveal>

        <div className="mt-12 grid gap-6 md:grid-cols-3">
          {testimonials.map((t, i) => (
            <Reveal key={t.name} delay={i * 100}>
              <figure className="flex h-full flex-col rounded-2xl bg-white p-6 shadow-sm">
                <Stars count={t.rating} />
                <blockquote className="mt-4 flex-1 text-sm leading-relaxed text-espresso-700">
                  “{t.quote}”
                </blockquote>
                <figcaption className="mt-6 flex items-center gap-3 border-t border-espresso-100 pt-4">
                  <span
                    className="grid size-10 shrink-0 place-items-center rounded-full bg-espresso-800 font-display text-sm font-semibold text-espresso-50"
                    aria-hidden="true"
                  >
                    {t.name.charAt(0)}
                  </span>
                  <span>
                    <span className="block text-sm font-semibold text-espresso-900">{t.name}</span>
                    <span className="block text-xs text-espresso-500">{t.role}</span>
                  </span>
                </figcaption>
              </figure>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}
