import { Compass, Sparkles } from 'lucide-react'
import { Link } from 'react-router'

import { buttonVariants } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'

interface PlaceholderPageProps {
  title: string
  phase: number
}

/** Honest stand-in for a page whose feature is not built yet. */
export function PlaceholderPage({ title, phase }: PlaceholderPageProps) {
  return (
    <div className="flex min-h-[50vh] items-center justify-center p-4">
      <Card className="max-w-md text-center p-8">
        <CardContent className="flex flex-col items-center gap-4 pt-4">
          <div className="flex size-16 items-center justify-center rounded-2xl bg-primary/15 text-primary shadow-inner">
            <Compass className="size-8" aria-hidden />
          </div>
          <div className="space-y-1">
            <h1 className="font-display text-3xl font-bold tracking-tight text-foreground">
              {title}
            </h1>
            <p className="text-sm font-semibold text-muted-foreground">
              Our dinosaurs are currently building this area of the park!
            </p>
          </div>
          <div className="inline-flex items-center gap-2 rounded-full bg-accent/20 px-4 py-1.5 text-xs font-bold text-accent">
            <Sparkles className="size-3.5" aria-hidden />
            Coming in Phase {phase}
          </div>
          <div className="pt-2">
            <Link to="/" className={buttonVariants({ variant: 'outline' })}>
              Back to Home
            </Link>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
