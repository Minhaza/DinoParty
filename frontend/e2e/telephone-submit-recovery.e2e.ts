import { devices, expect, test, type Page } from '@playwright/test'

import { authHeaders, newPlayer, type Player } from './support'

/**
 * Dino Telephone submission recovery (P6-04): "sent" is not "accepted". Three players lose part of
 * a submission on the wire and must neither hang in "sending" nor lose their text:
 * - the submit frame never reaches the server → after the watchdog the player may send again;
 * - the ACK never reaches the player → the resync snapshot confirms the entry;
 * - the submit frame is lost and the socket drops → the reconnect snapshot offers a retry.
 * Frames are filtered by page-level WebSocket routes, which take precedence over the context route
 * of `newPlayer`.
 */

interface Wire {
  /** Drops the next `n` outgoing frames that contain `marker`. */
  dropOutgoing(marker: string, n: number): void
  /** Drops every incoming frame that contains `marker` until called again with `null`. */
  dropIncoming(marker: string | null): void
  cut(): Promise<void>
  restore(): void
}

async function wire(page: Page): Promise<Wire> {
  let outgoing = { marker: '', left: 0 }
  let incoming: string | null = null
  let blocked = false
  const live = new Set<() => Promise<void>>()
  await page.routeWebSocket(/\/ws$/, (ws) => {
    if (blocked) {
      void ws.close({ code: 4000, reason: 'e2e network cut' })
      return
    }
    const server = ws.connectToServer()
    live.add(async () => {
      await ws.close({ code: 4000, reason: 'e2e network cut' })
      await server.close()
    })
    ws.onMessage((m) => {
      if (typeof m === 'string' && outgoing.left > 0 && m.includes(outgoing.marker)) {
        outgoing.left -= 1
        return
      }
      server.send(m)
    })
    server.onMessage((m) => {
      if (typeof m === 'string' && incoming && m.includes(incoming)) return
      ws.send(m)
    })
  })
  return {
    dropOutgoing: (marker, n) => {
      outgoing = { marker, left: n }
    },
    dropIncoming: (marker) => {
      incoming = marker
    },
    cut: async () => {
      blocked = true
      for (const close of live) await close()
      live.clear()
    },
    restore: () => {
      blocked = false
    },
  }
}

test('a lost submission or a lost ACK never leaves a Telephone player stuck', async ({
  browser,
  request,
}) => {
  const suffix = Math.floor(Math.random() * 1e6)
  const desktop = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } }
  const players: Player[] = []
  for (const name of ['Rex', 'Trix', 'Ptera', 'Steg']) {
    players.push(await newPlayer(browser, request, `${name}${suffix}`, desktop))
  }
  const [host, lostSend, lostAck, dropped] = players
  const lostSendWire = await wire(lostSend.page)
  const lostAckWire = await wire(lostAck.page)
  const droppedWire = await wire(dropped.page)

  const box = (p: Player) => p.page.getByRole('textbox', { name: /your prompt/i })
  const send = (p: Player) => p.page.getByRole('button', { name: /send prompt/i })
  const waiting = (p: Player) => p.page.getByText(/your prompt is on its way/i)
  const retryNote = (p: Player) => p.page.getByText(/no answer from the server|not received/i)

  await test.step('four players start a Telephone game with a long writing step', async () => {
    const created = await request.post('/api/v1/rooms', {
      headers: authHeaders(host),
      // long enough that no deadline ends the step while the watchdog runs
      data: { gameKey: 'telephone', settings: { promptSeconds: 120 } },
    })
    expect(created.ok(), await created.text()).toBe(true)
    const { code } = (await created.json()) as { code: string }
    for (const p of players.slice(1)) {
      const joined = await request.post(`/api/v1/rooms/${code}/join`, { headers: authHeaders(p) })
      expect(joined.ok(), await joined.text()).toBe(true)
    }
    for (const p of players) {
      await p.page.goto(`/rooms/${code}`)
      await expect(p.page.getByRole('button', { name: 'Ready up' })).toBeVisible()
    }
    await host.page.getByRole('button', { name: 'Start game' }).click()
    for (const p of players) await expect(box(p)).toBeVisible({ timeout: 20_000 })
  })

  await test.step('submit frame lost: the watchdog unlocks a retry and keeps the text', async () => {
    lostSendWire.dropOutgoing('TELEPHONE_SUBMIT_TEXT', 1)
    await box(lostSend).fill('A lost prompt zq')
    await send(lostSend).click()
    await expect(send(lostSend)).toBeDisabled()
    await expect(lostSend.page.getByText(/no answer from the server/i)).toBeVisible({
      timeout: 15_000,
    })
    await expect(box(lostSend)).toHaveValue('A lost prompt zq')
    await send(lostSend).click()
    await expect(waiting(lostSend)).toBeVisible()
  })

  await test.step('ACK lost: the entry stays accepted after the watchdog', async () => {
    lostAckWire.dropIncoming('ACTION_ACK')
    await box(lostAck).fill('An unacknowledged prompt zq')
    // The watchdog (10 s) fires on the missing ACK and resyncs over REST; that snapshot must
    // confirm the entry, not reopen it.
    const resync = lostAck.page.waitForResponse((r) => r.url().endsWith('/snapshot'), {
      timeout: 20_000,
    })
    const sentAt = Date.now()
    await send(lostAck).click()
    await expect(waiting(lostAck)).toBeVisible()
    expect((await resync).ok()).toBe(true)
    // it is the watchdog's resync, not an unrelated one
    expect(Date.now() - sentAt).toBeGreaterThanOrEqual(9_000)
    lostAckWire.dropIncoming(null)
    await expect(waiting(lostAck)).toBeVisible()
    await expect(retryNote(lostAck)).toHaveCount(0)
    await expect(send(lostAck)).toHaveCount(0)
  })

  await test.step('submit frame lost and socket dropped: the reconnect offers a retry', async () => {
    droppedWire.dropOutgoing('TELEPHONE_SUBMIT_TEXT', 1)
    await box(dropped).fill('A prompt sent into the void zq')
    await send(dropped).click()
    await droppedWire.cut()
    await expect(dropped.page.getByText(/Reconnecting/).first()).toBeVisible()
    droppedWire.restore()
    await expect(dropped.page.getByText(/Reconnecting/)).toHaveCount(0, { timeout: 30_000 })
    await expect(dropped.page.getByText(/not received/i)).toBeVisible()
    await expect(box(dropped)).toHaveValue('A prompt sent into the void zq')
    await send(dropped).click()
    await expect(waiting(dropped)).toBeVisible()
  })

  await test.step('the last prompt moves everyone to the drawing step together', async () => {
    await box(host).fill('The host prompt zq')
    await send(host).click()
    for (const p of players) {
      await expect(p.page.getByRole('heading', { name: 'Step 2 of 4' })).toBeVisible()
    }
  })
})
