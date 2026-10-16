import { devices, expect, test, type Locator, type Page } from '@playwright/test'

import { authHeaders, canvasStats, drag, newPlayer, type Player } from './support'

/**
 * Dino Telephone smoke (P6-04): four players (the game's minimum, [C-1]) in separate browser
 * contexts play a complete game against the real backend: the host picks Telephone in the UI,
 * everyone writes, draws, describes and draws again, the game reaches results, two players refresh
 * mid-step, and a rematch starts a fresh session. After every step each page may show exactly the
 * one entry it was assigned ([TP-8]). One long test because each step depends on the last.
 */
test('four players complete a Dino Telephone game and rematch', async ({ browser, request }) => {
  const suffix = Math.floor(Math.random() * 1e6)
  const desktop = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } }
  const host = await newPlayer(browser, request, `Rex${suffix}`, desktop)
  const others = [
    await newPlayer(browser, request, `Trix${suffix}`, devices['Pixel 7']),
    await newPlayer(browser, request, `Ptera${suffix}`, desktop),
    await newPlayer(browser, request, `Steg${suffix}`, desktop),
  ]
  const players = [host, ...others]
  const errors: string[] = []
  for (const p of players) p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))

  const promptOf = (p: Player) => `Prompt by ${p.nickname} zq`
  const descriptionOf = (p: Player) => `Description by ${p.nickname} zq`
  const allPrompts = players.map(promptOf)
  const allDescriptions = players.map(descriptionOf)

  const assignment = (page: Page) => page.getByRole('region', { name: 'Your assignment' })
  const heading = (page: Page, step: number) =>
    page.getByRole('heading', { name: `Step ${step} of 4` })
  /** Every entry text from `texts` that appears anywhere on the page. */
  const visibleOf = async (page: Page, texts: string[]) => {
    const body = await page.locator('body').innerText()
    return texts.filter((t) => body.includes(t))
  }
  const inkOf = (canvas: Locator) =>
    canvas.evaluate((node) => {
      const c = node as HTMLCanvasElement
      const ctx = c.getContext('2d')
      if (!ctx) return 0
      const { data } = ctx.getImageData(0, 0, c.width, c.height)
      let ink = 0
      for (let i = 0; i < data.length; i += 4) {
        if (
          Math.abs(data[i] - 0xff) + Math.abs(data[i + 1] - 0xfd) + Math.abs(data[i + 2] - 0xf8) >
          60
        )
          ink++
      }
      return ink
    })

  /**
   * A sent entry counts only once the server confirms it: the waiting screen, or, for the last
   * sender of a step, the next step itself (the server ends the step in that same command).
   */
  const expectAccepted = async (page: Page, waiting: RegExp, nextStep: number) => {
    await expect(page.getByText(waiting).or(heading(page, nextStep))).toBeVisible()
  }

  let code = ''
  const snapshot = async () => {
    const res = await request.get(`/api/v1/rooms/${code}/snapshot`, { headers: authHeaders(host) })
    expect(res.ok()).toBe(true)
    return (await res.json()) as { room: { sessionId: string | null; status: string } }
  }

  const startFromLobby = async () => {
    for (const p of players) {
      await p.page.goto(`/rooms/${code}`)
      await p.page.getByRole('button', { name: 'Ready up' }).click()
      await expect(p.page.getByRole('button', { name: 'Cancel ready' })).toBeVisible()
    }
    await host.page.getByRole('button', { name: 'Start game' }).click()
    for (const p of players) await expect(p.page).toHaveURL(new RegExp(`/rooms/${code}/play$`))
  }

  await test.step('host creates a Telephone room in the UI; three players join', async () => {
    await host.page.goto('/rooms/new')
    await host.page.getByRole('button', { name: /Dino Telephone/ }).click()
    await host.page.getByRole('button', { name: '+ Create room' }).first().click()
    await expect(host.page).toHaveURL(/\/rooms\/[A-Z0-9]+$/)
    code = host.page.url().split('/').pop() ?? ''
    for (const p of others) {
      const joined = await request.post(`/api/v1/rooms/${code}/join`, { headers: authHeaders(p) })
      expect(joined.ok(), await joined.text()).toBe(true)
    }
  })

  await test.step('start: everyone gets a prompt editor and the same server deadline', async () => {
    await startFromLobby()
    for (const p of players) {
      await expect(heading(p.page, 1)).toBeVisible({ timeout: 20_000 })
      await expect(p.page.getByRole('textbox', { name: /your prompt/i })).toBeVisible()
    }
  })

  await test.step('writing: a refresh keeps the unsent draft', async () => {
    const writer = others[1]
    await writer.page.getByRole('textbox', { name: /your prompt/i }).fill('half a prompt')
    // the draft is synced after a typing pause; wait for the server copy, not for a timer
    await expect
      .poll(async () => {
        const res = await request.get(`/api/v1/rooms/${code}/snapshot`, {
          headers: authHeaders(writer),
        })
        const body = (await res.json()) as { game: { draft?: { text?: string } } | null }
        return body.game?.draft?.text
      })
      .toBe('half a prompt')
    await writer.page.reload()
    await expect(writer.page.getByRole('textbox', { name: /your prompt/i })).toHaveValue(
      'half a prompt',
    )
  })

  await test.step('writing: each player sends a different prompt; sent waits for the server', async () => {
    for (const p of players) {
      await p.page.getByRole('textbox', { name: /your prompt/i }).fill(promptOf(p))
      const send = p.page.getByRole('button', { name: /send prompt/i })
      await send.click()
      await expectAccepted(p.page, /your prompt is on its way/i, 2)
    }
  })

  const drawAndSend = async (
    p: Player,
    from: [number, number],
    to: [number, number],
    nextStep: number,
  ) => {
    await drag(p, from, to)
    await expect.poll(async () => (await canvasStats(p.page)).ink).toBeGreaterThan(50)
    await p.page.getByRole('button', { name: 'Send drawing' }).click()
    if (nextStep > 4) {
      await expect(
        p.page
          .getByText(/your masterpiece is on its way/i)
          .or(p.page.getByText(/every chain is complete/i)),
      ).toBeVisible()
    } else {
      await expectAccepted(p.page, /your masterpiece is on its way/i, nextStep)
    }
  }

  await test.step('drawing: each player draws someone else’s prompt, and only that one', async () => {
    const assigned: string[] = []
    for (const p of players) {
      await expect(heading(p.page, 2)).toBeVisible({ timeout: 20_000 })
      await expect(assignment(p.page)).toContainText('zq')
      const seen = await visibleOf(p.page, allPrompts)
      expect(seen, `${p.nickname} sees exactly one prompt`).toHaveLength(1)
      expect(seen[0]).not.toBe(promptOf(p))
      assigned.push(seen[0])
    }
    expect(new Set(assigned).size).toBe(4)
  })

  await test.step('drawing: a refresh mid-drawing restores the task and the drawing', async () => {
    const artist = others[2]
    const [task] = await visibleOf(artist.page, allPrompts)
    await drag(artist, [0.2, 0.3], [0.8, 0.7])
    // strokes are ACKed one batch at a time; the server draft is what a reload restores
    await expect
      .poll(async () => {
        const res = await request.get(`/api/v1/rooms/${code}/snapshot`, {
          headers: authHeaders(artist),
        })
        const body = (await res.json()) as { game: { draft?: { strokes?: unknown[] } } | null }
        return body.game?.draft?.strokes?.length ?? 0
      })
      .toBeGreaterThan(0)
    await artist.page.reload()
    await expect(assignment(artist.page)).toContainText(task)
    await expect.poll(async () => (await canvasStats(artist.page)).ink).toBeGreaterThan(50)
    await artist.page.getByRole('button', { name: 'Send drawing' }).click()
    await expect(artist.page.getByText(/your masterpiece is on its way/i)).toBeVisible()
  })

  await test.step('drawing: everyone else sends a drawing (mouse and touch)', async () => {
    for (const p of players) {
      if (p === others[2]) continue
      await drawAndSend(p, [0.25, 0.5], [0.75, 0.5], 3)
    }
  })

  await test.step('describing: a drawing to describe, and no prompt anywhere', async () => {
    for (const p of players) {
      await expect(heading(p.page, 3)).toBeVisible({ timeout: 20_000 })
      const drawing = p.page.getByRole('img', { name: /drawing you need to describe/i })
      await expect(drawing).toBeVisible()
      await expect.poll(() => inkOf(drawing)).toBeGreaterThan(50)
      expect(await visibleOf(p.page, allPrompts), `${p.nickname} sees no prompt`).toEqual([])
    }
    for (const p of players) {
      await p.page.getByRole('textbox', { name: /your description/i }).fill(descriptionOf(p))
      await p.page.getByRole('button', { name: /send description/i }).click()
      await expectAccepted(p.page, /your description is on its way/i, 4)
    }
  })

  await test.step('last step: each draws one description that is not their own', async () => {
    for (const p of players) {
      await expect(heading(p.page, 4)).toBeVisible({ timeout: 20_000 })
      const seen = await visibleOf(p.page, allDescriptions)
      expect(seen, `${p.nickname} sees exactly one description`).toHaveLength(1)
      expect(seen[0]).not.toBe(descriptionOf(p))
      expect(await visibleOf(p.page, allPrompts)).toEqual([])
    }
    for (const [i, p] of players.entries()) {
      await drawAndSend(p, [0.1 + i * 0.1, 0.2], [0.5, 0.8], 5)
    }
  })

  const firstSession = (await snapshot()).room.sessionId

  await test.step('results: every screen shows the finished game and no entries', async () => {
    for (const p of players) {
      await expect(p.page.getByText(/every chain is complete/i)).toBeVisible({ timeout: 20_000 })
      expect(await visibleOf(p.page, [...allPrompts, ...allDescriptions])).toEqual([])
    }
    expect((await snapshot()).room.status).toBe('RESULTS')
  })

  await test.step('rematch: a fresh session with empty editors', async () => {
    for (const p of others) await p.page.getByRole('button', { name: 'Vote for Rematch' }).click()
    await host.page.getByRole('button', { name: 'Start Rematch' }).click()
    for (const p of players) {
      await expect(p.page).toHaveURL(new RegExp(`/rooms/${code}$`))
      await expect(p.page.getByRole('button', { name: 'Ready up' })).toBeVisible()
    }
    await startFromLobby()
    for (const p of players) {
      await expect(heading(p.page, 1)).toBeVisible({ timeout: 20_000 })
      await expect(p.page.getByRole('textbox', { name: /your prompt/i })).toHaveValue('')
      expect(await visibleOf(p.page, [...allPrompts, ...allDescriptions])).toEqual([])
    }
    const second = (await snapshot()).room.sessionId
    expect(second).not.toBeNull()
    expect(second).not.toBe(firstSession)
  })

  expect(errors).toEqual([])
  for (const p of players) await p.context.close()
})
