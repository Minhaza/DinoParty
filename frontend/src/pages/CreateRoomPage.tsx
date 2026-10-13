import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router'

import { buttonVariants } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { CreateRoomForm } from '@/features/rooms/CreateRoomForm'

export function CreateRoomPage() {
  return (
    <section className="mx-auto flex w-full max-w-4xl flex-col gap-5">
      <header className="flex items-center gap-4">
        <Link
          to="/"
          aria-label="Back to home"
          className={buttonVariants({ variant: 'outline', size: 'icon' })}
        >
          <ArrowLeft className="size-5" aria-hidden />
        </Link>
        <div>
          <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">Create room</h1>
          <p className="text-sm font-semibold text-foreground/75">
            Pick a game and settings, then invite your friends.
          </p>
        </div>
      </header>
      <Card>
        <CardContent className="pt-6 sm:pt-7">
          <CreateRoomForm layout="page" />
        </CardContent>
      </Card>
    </section>
  )
}
