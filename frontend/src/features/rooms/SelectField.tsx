import { useId } from 'react'

export interface SelectFieldProps {
  label: string
  value: number
  options: { value: number; label: string }[]
  onChange: (value: number) => void
  disabled?: boolean
}

/** Labelled native select; used for room settings so keyboard and screen readers just work. */
export function SelectField({ label, value, options, onChange, disabled }: SelectFieldProps) {
  const id = useId()
  // Keep an out-of-list current value selectable instead of silently showing the wrong option.
  const all = options.some((o) => o.value === value)
    ? options
    : [{ value, label: String(value) }, ...options]
  return (
    <div className="flex items-center justify-between gap-4">
      <label htmlFor={id} className="text-sm font-medium text-foreground">
        {label}
      </label>
      <select
        id={id}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        className="h-10 rounded-xl border-2 border-input bg-secondary/60 px-3 text-sm font-bold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60"
      >
        {all.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  )
}
