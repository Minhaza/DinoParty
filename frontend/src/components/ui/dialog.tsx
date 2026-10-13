import * as React from 'react'
import { X } from 'lucide-react'

import { cn } from '@/lib/utils'

interface DialogContextValue {
  open: boolean
  setOpen: (open: boolean) => void
  titleId: string
  descriptionId: string
}

const DialogContext = React.createContext<DialogContextValue | null>(null)

function useDialogContext() {
  const context = React.useContext(DialogContext)
  if (!context) {
    throw new Error('Dialog components must be rendered inside a <Dialog> root.')
  }
  return context
}

export interface DialogProps {
  open?: boolean
  defaultOpen?: boolean
  onOpenChange?: (open: boolean) => void
  children: React.ReactNode
}

export function Dialog({
  open: controlledOpen,
  defaultOpen = false,
  onOpenChange,
  children,
}: DialogProps) {
  const [uncontrolledOpen, setUncontrolledOpen] = React.useState(defaultOpen)
  const isControlled = controlledOpen !== undefined
  const open = isControlled ? controlledOpen : uncontrolledOpen

  const setOpen = React.useCallback(
    (next: boolean) => {
      if (!isControlled) {
        setUncontrolledOpen(next)
      }
      onOpenChange?.(next)
    },
    [isControlled, onOpenChange],
  )

  const id = React.useId()
  const titleId = `dialog-title-${id}`
  const descriptionId = `dialog-desc-${id}`

  return (
    <DialogContext.Provider value={{ open, setOpen, titleId, descriptionId }}>
      {children}
    </DialogContext.Provider>
  )
}

export interface DialogTriggerProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  asChild?: boolean
}

export function DialogTrigger({
  asChild = false,
  onClick,
  children,
  className,
  ...props
}: DialogTriggerProps) {
  const { setOpen } = useDialogContext()

  const handleClick = (e: React.MouseEvent<HTMLButtonElement>) => {
    onClick?.(e)
    if (!e.defaultPrevented) {
      setOpen(true)
    }
  }

  if (asChild && React.isValidElement(children)) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const childProps = (children as React.ReactElement<any>).props
    return React.cloneElement(
      children as React.ReactElement<{ onClick?: React.MouseEventHandler }>,
      {
        onClick: (e: React.MouseEvent) => {
          childProps.onClick?.(e)
          if (!e.defaultPrevented) setOpen(true)
        },
      },
    )
  }

  return (
    <button type="button" onClick={handleClick} className={className} {...props}>
      {children}
    </button>
  )
}

export interface DialogContentProps extends React.HTMLAttributes<HTMLDivElement> {
  showCloseButton?: boolean
}

export function DialogContent({
  className,
  children,
  showCloseButton = true,
  ...props
}: DialogContentProps) {
  const { open, setOpen, titleId, descriptionId } = useDialogContext()
  const contentRef = React.useRef<HTMLDivElement>(null)

  // Handle ESC key and scroll lock
  React.useEffect(() => {
    if (!open) return

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        setOpen(false)
      }
    }

    const originalOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    window.addEventListener('keydown', handleKeyDown)

    // Focus first focusable element inside dialog
    const focusTimer = setTimeout(() => {
      if (contentRef.current) {
        const focusable = contentRef.current.querySelector<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
        )
        focusable?.focus()
      }
    }, 50)

    return () => {
      document.body.style.overflow = originalOverflow
      window.removeEventListener('keydown', handleKeyDown)
      clearTimeout(focusTimer)
    }
  }, [open, setOpen])

  if (!open) return null

  return (
    <div
      role="presentation"
      className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6"
    >
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-[#10213d]/55 backdrop-blur-xs transition-opacity animate-in fade-in"
        onClick={() => setOpen(false)}
        aria-hidden="true"
      />

      {/* Modal Dialog */}
      <div
        ref={contentRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        className={cn(
          'relative z-50 flex max-h-[90vh] w-full max-w-lg flex-col overflow-y-auto rounded-[26px] border-2 border-border bg-card p-6 text-card-foreground shadow-card transition-all sm:p-8 animate-in zoom-in-95',
          className,
        )}
        onClick={(e) => e.stopPropagation()}
        {...props}
      >
        {showCloseButton && (
          <button
            type="button"
            onClick={() => setOpen(false)}
            aria-label="Close dialog"
            className="absolute right-5 top-5 inline-flex size-8 items-center justify-center rounded-full bg-muted/60 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
          >
            <X className="size-4" />
          </button>
        )}
        {children}
      </div>
    </div>
  )
}

export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-2 text-left', className)} {...props} />
}

export function DialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end sm:gap-3',
        className,
      )}
      {...props}
    />
  )
}

export function DialogTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  const { titleId } = useDialogContext()
  return (
    <h2
      id={titleId}
      className={cn(
        'font-display text-xl font-semibold tracking-tight text-foreground sm:text-2xl',
        className,
      )}
      {...props}
    />
  )
}

export function DialogDescription({
  className,
  ...props
}: React.HTMLAttributes<HTMLParagraphElement>) {
  const { descriptionId } = useDialogContext()
  return (
    <p id={descriptionId} className={cn('text-sm text-muted-foreground', className)} {...props} />
  )
}

export function DialogClose({
  children,
  onClick,
  className,
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const { setOpen } = useDialogContext()

  return (
    <button
      type="button"
      onClick={(e) => {
        onClick?.(e)
        if (!e.defaultPrevented) {
          setOpen(false)
        }
      }}
      className={className}
      {...props}
    >
      {children}
    </button>
  )
}
