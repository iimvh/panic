import { useEffect, useRef, useState } from 'react'

/**
 * Bọc nội dung để nó mờ dần và trượt lên khi cuộn tới.
 * Dùng IntersectionObserver, không cần thư viện ngoài.
 */
export function Reveal({ children, delay = 0, className = '', as: Tag = 'div' }) {
  const ref = useRef(null)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return

    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true)
      return
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            setVisible(true)
            observer.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.12, rootMargin: '0px 0px -60px 0px' },
    )

    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  return (
    <Tag
      ref={ref}
      className={`reveal ${visible ? 'is-visible' : ''} ${className}`}
      style={delay ? { transitionDelay: `${delay}ms` } : undefined}
    >
      {children}
    </Tag>
  )
}

/** Tiêu đề section dùng chung: eyebrow + title + mô tả. */
export function SectionHeading({ eyebrow, title, description, align = 'left', light = false }) {
  const alignment = align === 'center' ? 'text-center mx-auto items-center' : 'text-left'
  return (
    <div className={`flex max-w-2xl flex-col gap-4 ${alignment}`}>
      {eyebrow && (
        <span className={`eyebrow ${light ? 'text-honey-300' : ''}`}>{eyebrow}</span>
      )}
      <h2 className={`section-title ${light ? 'text-espresso-50' : ''}`}>{title}</h2>
      {description && (
        <p className={`text-base leading-relaxed ${light ? 'text-espresso-200' : 'text-espresso-700'}`}>
          {description}
        </p>
      )}
    </div>
  )
}

/** Định dạng giá tiền Việt Nam: 185000 -> 185.000 ₫ */
export function formatPrice(value) {
  return `${value.toLocaleString('vi-VN')} ₫`
}
