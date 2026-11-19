import { about } from '../data/content.js'
import { Reveal, SectionHeading } from './Section.jsx'

export default function About() {
  return (
    <section id="gioi-thieu" className="scroll-mt-24 bg-espresso-50 py-20 sm:py-28">
      <div className="container-page grid items-center gap-12 lg:grid-cols-2 lg:gap-16">
        <Reveal className="order-2 lg:order-1">
          <SectionHeading eyebrow={about.eyebrow} title={about.title} />

          <div className="mt-6 space-y-4 text-base leading-relaxed text-espresso-700">
            {about.paragraphs.map((p) => (
              <p key={p.slice(0, 24)}>{p}</p>
            ))}
          </div>

          <ul className="mt-8 grid gap-3 sm:grid-cols-2">
            {about.highlights.map((item) => (
              <li key={item} className="flex items-start gap-2.5 text-sm text-espresso-800">
                <svg
                  viewBox="0 0 24 24"
                  className="mt-0.5 size-5 shrink-0 text-leaf-500"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.2"
                  aria-hidden="true"
                >
                  <path d="M20 6L9 17l-5-5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </Reveal>

        <Reveal delay={120} className="order-1 lg:order-2">
          <figure className="relative">
            <div className="overflow-hidden rounded-3xl shadow-xl shadow-espresso-900/15">
              <img
                src={about.image}
                alt={about.imageCaption}
                loading="lazy"
                className="aspect-4/5 w-full object-cover sm:aspect-3/2 lg:aspect-4/5"
              />
            </div>
            <figcaption className="absolute bottom-4 left-4 rounded-full bg-espresso-950/70 px-4 py-2 text-xs font-medium text-espresso-50 backdrop-blur">
              {about.imageCaption}
            </figcaption>
            <div
              className="absolute -bottom-6 -right-4 hidden rounded-2xl bg-honey-400 px-5 py-4 text-espresso-950 shadow-lg sm:block"
              aria-hidden="true"
            >
              <span className="block font-display text-2xl font-semibold">25+</span>
              <span className="text-xs font-medium">năm trên đồi</span>
            </div>
          </figure>
        </Reveal>
      </div>
    </section>
  )
}
