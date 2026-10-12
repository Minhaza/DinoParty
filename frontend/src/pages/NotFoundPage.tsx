import { Link } from 'react-router'

import { buttonVariants } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <section className="flex flex-col items-start gap-3">
      <h1 className="text-3xl font-bold tracking-tight">Page not found</h1>
      <p className="text-muted-foreground">That page does not exist.</p>
      <Link to="/" className={buttonVariants({ variant: 'outline' })}>
        Back to home
      </Link>
    </section>
  )
}
