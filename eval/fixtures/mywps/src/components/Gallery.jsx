import { gallery } from '../data/content.js'
import { Reveal, SectionHeading } from './Section.jsx'

export default function Gallery() {
  return (
    <section id="thu-vien" className="scroll-mt-24 bg-white py-20 sm:py-28">
      <div className="container-page">
        <Reveal>
          <SectionHeading
            eyebrow="Thư viện ảnh"
            title="Nông trại qua bốn mùa"
            description="Mùa khô hoa nở trắng đồi, mùa mưa quả chín đỏ, mùa thu hoạch sân gạch đầy cà phê phơi."
            align="center"
          />
        </Reveal>

        <div className="mt-12 grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3">
          {gallery.map((img, i) => (
            <Reveal
              key={img.src}
              delay={i * 70}
              className={i === 0 ? 'col-span-2 lg:col-span-1 lg:row-span-2' : ''}
            >
              <figure className="group h-full overflow-hidden rounded-xl">
                <img
                  src={img.src}
                  alt={img.alt}
                  loading="lazy"
                  className={`w-full object-cover transition-transform duration-500 group-hover:scale-105 ${
                    i === 0 ? 'aspect-16/10 lg:aspect-4/5' : 'aspect-square'
                  }`}
                />
              </figure>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  )
}
