import * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'

import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex shrink-0 items-center justify-center gap-2 rounded-full font-semibold whitespace-nowrap transition-all duration-150 outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 active:scale-[0.98] cursor-pointer [&_svg]:pointer-events-none [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default:
          'bg-primary text-primary-foreground shadow-sm hover:bg-primary/90 hover:shadow-md hover:-translate-y-0.5',
        secondary:
          'bg-secondary text-secondary-foreground shadow-xs hover:bg-secondary/80 border border-border/60 hover:-translate-y-0.5',
        accent:
          'bg-accent text-accent-foreground shadow-sm hover:bg-accent/90 hover:shadow-md hover:-translate-y-0.5',
        outline:
          'border-2 border-border/80 bg-background/80 shadow-xs hover:bg-muted hover:border-foreground/20 hover:-translate-y-0.5',
        ghost: 'hover:bg-muted/70 text-foreground',
        destructive:
          'bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90 hover:-translate-y-0.5',
      },
      size: {
        default: 'h-10 px-5 py-2 text-sm',
        sm: 'h-8 gap-1.5 px-3.5 text-xs',
        lg: 'h-12 px-7 text-base font-bold',
        xl: 'h-14 px-8 text-lg font-bold gap-3 shadow-md',
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
