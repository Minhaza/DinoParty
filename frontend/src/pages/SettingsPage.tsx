import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { GuestProfileForm } from '@/features/guest/GuestProfileForm'
import { RequireGuest } from '@/features/guest/RequireGuest'

export function SettingsPage() {
  return (
    <section className="mx-auto w-full max-w-xl">
      <Card>
        <CardHeader>
          <CardTitle as="h1">Settings</CardTitle>
          <CardDescription>
            Your guest profile. It is visible to everyone in your room and stays with this browser.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <RequireGuest>
            {(guest) => <GuestProfileForm key={guest.id} guest={guest} />}
          </RequireGuest>
        </CardContent>
      </Card>
    </section>
  )
}
