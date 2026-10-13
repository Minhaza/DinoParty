import * as React from 'react'

import { cn } from '@/lib/utils'

function Card({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      className={cn(
        'flex flex-col rounded-[22px] border-2 border-border bg-card text-card-foreground shadow-card transition-all',
        className,
      )}
      {...props}
    />
  )
}

function CardHeader({ className, ...props }: React.ComponentProps<'div'>) {
  return <div className={cn('flex flex-col gap-2 p-6 sm:p-7', className)} {...props} />
}

function CardTitle({
  className,
  as: Heading = 'h2',
  ...props
}: React.ComponentProps<'h2'> & { as?: 'h1' | 'h2' | 'h3' }) {
  return (
    <Heading
      className={cn(
        'font-display text-xl font-semibold tracking-tight text-foreground sm:text-2xl',
        className,
      )}
      {...props}
    />
  )
}

function CardDescription({ className, ...props }: React.ComponentProps<'p'>) {
  return <p className={cn('text-sm text-muted-foreground leading-relaxed', className)} {...props} />
}

function CardContent({ className, ...props }: React.ComponentProps<'div'>) {
  return <div className={cn('px-6 pb-6 sm:px-7 sm:pb-7', className)} {...props} />
}

function CardFooter({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div className={cn('flex items-center px-6 pb-6 pt-0 sm:px-7 sm:pb-7', className)} {...props} />
  )
}

export { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter }
