import { useId } from 'react'

export interface SelectFieldProps<T extends number | string> {
  label: string
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
  disabled?: boolean
  /** Server message for this field; marks the select invalid and describes it. */
  error?: string | null
}

/** Labelled native select; used for room settings so keyboard and screen readers just work. */
export function SelectField<T extends number | string>({
  label,
  value,
  options,
  onChange,
  disabled,
  error,
}: SelectFieldProps<T>) {
  const id = useId()
  const errorId = `${id}-error`
  // Keep an out-of-list current value selectable instead of silently showing the wrong option.
  const all = options.some((o) => o.value === value)
    ? options
    : [{ value, label: String(value) }, ...options]
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between gap-4">
        <label htmlFor={id} className="text-sm font-medium text-foreground">
          {label}
        </label>
        <select
          id={id}
          value={String(value)}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(e) => {
            const picked = all.find((o) => String(o.value) === e.target.value)
            if (picked) onChange(picked.value)
          }}
          className="h-10 rounded-xl border-2 border-input bg-secondary/60 px-3 text-sm font-bold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60 aria-invalid:border-destructive"
        >
          {all.map((o) => (
            <option key={String(o.value)} value={String(o.value)}>
              {o.label}
            </option>
          ))}
        </select>
      </div>
      {error && (
        <p id={errorId} className="text-right text-xs font-semibold text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
