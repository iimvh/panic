import { process } from '../data/content.js'
import { Reveal, SectionHeading } from './Section.jsx'

export default function Process() {
  return (
    <section id="quy-trinh" className="relative scroll-mt-24 overflow-hidden bg-espresso-900 py-20 text-espresso-50 sm:py-28">
      {/* Hoạ tiết nền nhẹ */}
      <div
        className="pointer-events-none absolute inset-0 opacity-[0.06]"
        style={{
          backgroundImage:
            'radial-gradient(circle at 1px 1px, #f2cf7d 1px, transparent 0)',
          backgroundSize: '22px 22px',
        }}
        aria-hidden="true"
      />

      <div className="container-page relative">
        <Reveal>
          <SectionHeading
            eyebrow="Từ quả đến tách"
            title="Bốn bước, không tắt bước nào"
            description="Chúng tôi giữ quy trình thủ công vì chất lượng nằm ở những chi tiết nhỏ nhất."
            light
          />
        </Reveal>

        <ol className="mt-14 grid gap-8 sm:grid-cols-2 lg:grid-cols-4 lg:gap-6">
          {process.map((item, i) => (
            <Reveal key={item.step} delay={i * 100} as="li" className="relative">
              {/* Đường nối giữa các bước trên desktop */}
              {i < process.length - 1 && (
                <span
                  className="absolute left-12 top-6 hidden h-px w-[calc(100%-2rem)] bg-espresso-50/20 lg:block"
                  aria-hidden="true"
                />
              )}
              <div className="relative">
                <span className="grid size-12 place-items-center rounded-full border border-honey-300/50 bg-espresso-950 font-display text-lg font-semibold text-honey-300">
                  {item.step}
                </span>
                <h3 className="mt-5 font-display text-xl text-espresso-50">{item.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-espresso-200">{item.text}</p>
              </div>
            </Reveal>
          ))}
        </ol>
      </div>
    </section>
  )
}
