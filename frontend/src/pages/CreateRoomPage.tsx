import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { CreateRoomForm } from '@/features/rooms/CreateRoomForm'

export function CreateRoomPage() {
  return (
    <section className="mx-auto w-full max-w-xl">
      <Card>
        <CardHeader>
          <CardTitle as="h1">Create room</CardTitle>
          <CardDescription>Pick a game and settings, then invite your friends.</CardDescription>
        </CardHeader>
        <CardContent>
          <CreateRoomForm />
        </CardContent>
      </Card>
    </section>
  )
}
