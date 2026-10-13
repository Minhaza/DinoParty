import { useParams } from 'react-router'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { JoinRoomForm } from '@/features/rooms/JoinRoomForm'

export function JoinRoomPage() {
  const { code } = useParams()
  return (
    <section className="mx-auto w-full max-w-md pt-10">
      <Card className="relative">
        <CardHeader>
          <CardTitle as="h1">Join room</CardTitle>
          <CardDescription>
            Enter the room code your host shared, or open an invite link.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <JoinRoomForm key={code ?? ''} initialCode={code ?? ''} />
        </CardContent>
      </Card>
    </section>
  )
}
