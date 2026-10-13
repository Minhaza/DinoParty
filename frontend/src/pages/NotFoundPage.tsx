import { HelpCircle } from 'lucide-react'
import { Link } from 'react-router'

import { buttonVariants } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'

export function NotFoundPage() {
  return (
    <div className="flex min-h-[50vh] items-center justify-center p-4">
      <Card className="max-w-md text-center p-8">
        <CardContent className="flex flex-col items-center gap-4 pt-4">
          <div className="flex size-16 items-center justify-center rounded-2xl bg-destructive/15 text-destructive shadow-inner">
            <HelpCircle className="size-8" aria-hidden />
          </div>
          <div className="space-y-1">
            <h1 className="font-display text-4xl font-bold tracking-tight text-foreground">
              Page not found
            </h1>
            <p className="text-sm font-semibold text-muted-foreground">
              We couldn't find the page you're looking for. Maybe a T-Rex took a bite out of it!
            </p>
          </div>
          <div className="pt-2">
            <Link to="/" className={buttonVariants({ variant: 'default' })}>
              Back to Safe Ground
            </Link>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
