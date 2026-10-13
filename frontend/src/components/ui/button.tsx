import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-full font-bold whitespace-nowrap transition-all duration-150 outline-none focus-visible:ring-[3px] focus-visible:ring-ring/60 focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 active:translate-y-0.5 active:shadow-none cursor-pointer [&_svg]:pointer-events-none [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default:
          'bg-primary text-primary-foreground shadow-tactile hover:-translate-y-0.5 hover:brightness-110',
        secondary:
          'bg-secondary text-secondary-foreground border-2 border-border shadow-tactile hover:-translate-y-0.5 hover:bg-muted',
        accent:
          'bg-accent text-accent-foreground shadow-tactile hover:-translate-y-0.5 hover:brightness-105',
        outline:
          'border-2 border-border bg-card text-foreground shadow-tactile hover:-translate-y-0.5 hover:border-primary/60',
        ghost: 'hover:bg-foreground/8 text-foreground',
        destructive:
          'bg-destructive text-destructive-foreground shadow-tactile hover:-translate-y-0.5 hover:brightness-110',
      },
      size: {
        default: 'h-10 px-5 py-2 text-sm',
        sm: 'h-8 gap-1.5 px-3.5 text-xs',
        lg: 'h-12 px-7 text-base',
        xl: 'h-14 px-8 text-lg gap-3',
        icon: 'size-10 rounded-full',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
)

type ButtonProps = React.ComponentProps<'button'> & VariantProps<typeof buttonVariants>

function Button({ className, variant, size, ...props }: ButtonProps) {
  return <button className={cn(buttonVariants({ variant, size, className }))} {...props} />
}

// shadcn convention: the variants helper is exported next to the component (also used by links).
// eslint-disable-next-line react-refresh/only-export-components
export { Button, buttonVariants }
