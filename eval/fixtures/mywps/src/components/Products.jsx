import { useState } from 'react'
import { products } from '../data/content.js'
import { Reveal, SectionHeading, formatPrice } from './Section.jsx'

function ProductCard({ product, onAdd }) {
  const [added, setAdded] = useState(false)

  const handleAdd = () => {
    onAdd(product)
    setAdded(true)
    window.setTimeout(() => setAdded(false), 1600)
  }

  return (
    <article className="card group flex flex-col overflow-hidden transition-shadow hover:shadow-lg hover:shadow-espresso-900/10">
      <div className="relative overflow-hidden">
        <img
          src={product.image}
          alt={product.name}
          loading="lazy"
          className="aspect-4/3 w-full object-cover transition-transform duration-500 group-hover:scale-105"
        />
        {product.badge && (
          <span className="absolute left-3 top-3 rounded-full bg-honey-400 px-3 py-1 text-[0.7rem] font-bold uppercase tracking-wide text-espresso-950">
            {product.badge}
          </span>
        )}
      </div>

      <div className="flex flex-1 flex-col p-5">
        <div className="flex items-start justify-between gap-3">
          <h3 className="font-display text-xl text-espresso-900">{product.name}</h3>
          <span className="shrink-0 rounded-full bg-leaf-100 px-2.5 py-1 text-[0.7rem] font-semibold text-leaf-700">
            {product.weight}
          </span>
        </div>

        <p className="mt-1 text-xs font-medium uppercase tracking-wide text-espresso-500">
          {product.roast}
        </p>
        <p className="mt-3 flex-1 text-sm leading-relaxed text-espresso-700">{product.note}</p>

        <div className="mt-5 flex items-center justify-between gap-3 border-t border-espresso-100 pt-4">
          <span className="font-display text-xl font-semibold text-espresso-900">
            {formatPrice(product.price)}
          </span>
          <button
            type="button"
            onClick={handleAdd}
            className={`btn px-4 py-2 text-xs ${
              added
                ? 'bg-leaf-600 text-white'
                : 'bg-espresso-800 text-espresso-50 hover:bg-espresso-900'
            }`}
            aria-live="polite"
          >
            {added ? 'Đã thêm ✓' : 'Thêm vào giỏ'}
          </button>
        </div>
      </div>
    </article>
  )
}

export default function Products({ onAdd }) {
  return (
    <section id="san-pham" className="scroll-mt-24 bg-white py-20 sm:py-28">
      <div className="container-page">
        <Reveal>
          <SectionHeading
            eyebrow="Cà phê của nông trại"
            title="Rang mộc, đóng gói theo mẻ nhỏ"
            description="Mỗi gói đều ghi ngày rang. Đặt từ 2 gói trở lên được miễn phí giao hàng toàn quốc."
            align="center"
          />
        </Reveal>

        <div className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {products.map((product, i) => (
            <Reveal key={product.id} delay={i * 90}>
              <ProductCard product={product} onAdd={onAdd} />
            </Reveal>
          ))}
        </div>

        <Reveal delay={120}>
          <p className="mt-10 text-center text-sm text-espresso-600">
            Cần mua số lượng lớn cho quán cà phê?{' '}
            <a href="#dat-tour" className="font-semibold text-leaf-700 underline underline-offset-4 hover:text-leaf-600">
              Liên hệ giá sỉ
            </a>
          </p>
        </Reveal>
      </div>
    </section>
  )
}
