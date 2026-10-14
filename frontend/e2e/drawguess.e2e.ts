import { devices, expect, test, type Page } from '@playwright/test'
import {
  authHeaders,
  BLUE,
  canvasOf,
  canvasStats,
  drag,
  GREEN,
  isNear,
  newPlayer,
  RED,
  secondsLeft,
  type Player,
} from './support'

/**
 * Draw & Guess smoke (P5-05): a desktop mouse player and a mobile touch player play a full game
 * against the real backend: lobby, word choice, drawing, secrecy, refresh, network loss, guessing,
 * timer expiry, results and rematch. One long test because every step depends on the game state
 * the previous one left behind.
 */
test('two players complete a Draw & Guess game and rematch', async ({ browser, request }) => {
  const suffix = Math.floor(Math.random() * 1e6)
  const host = await newPlayer(browser, request, `Desk${suffix}`, {
    ...devices['Desktop Chrome'],
    viewport: { width: 1280, height: 860 },
  })
  const mobile = await newPlayer(browser, request, `Phone${suffix}`, devices['Pixel 7'])
  const players = [host, mobile]
  const errors: string[] = []
  for (const p of players) p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))

  const created = await request.post('/api/v1/rooms', {
    headers: authHeaders(host),
    data: { gameKey: 'draw-guess', settings: { cycles: 1, drawSeconds: 30 } },
  })
  expect(created.status(), await created.text()).toBe(201)
  const code = ((await created.json()) as { code: string }).code
  const joined = await request.post(`/api/v1/rooms/${code}/join`, { headers: authHeaders(mobile) })
  expect(joined.ok(), await joined.text()).toBe(true)

  const nicknameOf = (p: Player) => p.nickname
  const other = (p: Player) => (p === host ? mobile : host)
  const snapshot = async () => {
    const res = await request.get(`/api/v1/rooms/${code}/snapshot`, { headers: authHeaders(host) })
    expect(res.ok()).toBe(true)
    return (await res.json()) as {
      room: { sessionId: string | null; status: string }
      game: { scores: Record<string, number> } | null
    }
  }

  /** Readies everyone in the lobby and starts from the host; both land on the game page. */
  const startFromLobby = async () => {
    for (const p of players) {
      await p.page.goto(`/rooms/${code}`)
      await p.page.getByRole('button', { name: 'Ready up' }).click()
      await expect(p.page.getByRole('button', { name: 'Cancel ready' })).toBeVisible()
    }
    await host.page.getByRole('button', { name: 'Start game' }).click()
    for (const p of players) await expect(p.page).toHaveURL(new RegExp(`/rooms/${code}/play$`))
  }

  /** Waits for the word-choice phase, picks the first word and returns the drawer and word. */
  const chooseWord = async (): Promise<{ drawer: Player; guesser: Player; word: string }> => {
    const pick = (p: Page) => p.getByRole('heading', { name: 'Pick Your Word!' })
    let drawer: Player | undefined
    await expect
      .poll(
        async () => {
          for (const p of players) if (await pick(p.page).isVisible()) drawer = p
          return drawer?.nickname
        },
        { timeout: 30_000 },
      )
      .toBeDefined()
    if (!drawer) throw new Error('no drawer')
    const guesser = other(drawer)
    await expect(guesser.page.getByText(`${drawer.nickname} is choosing a word`)).toBeVisible()
    const first = drawer.page
      .locator('div.fixed')
      .getByRole('button')
      .filter({ hasText: /\S/ })
      .first()
    const word = (await first.innerText()).trim()
    await first.click()
    await expect(drawer.page.getByText('Your word:')).toBeVisible()
    await expect(guesser.page.getByLabel(/^Word hint:/)).toBeVisible()
    return { drawer, guesser, word }
  }

  const scores = (p: Page) => p.getByRole('region', { name: 'Players and scores' })

  await test.step('lobby → game start', startFromLobby)

  // ------------------------------------------------------------------ round 1
  const r1 = await chooseWord()
  await test.step('round 1: secrecy and server-derived timer', async () => {
    await expect(r1.drawer.page.getByText(r1.word, { exact: true })).toBeVisible()
    await expect(r1.guesser.page.getByText(r1.word, { exact: true })).toHaveCount(0)
    const [a, b] = [await secondsLeft(r1.drawer.page), await secondsLeft(r1.guesser.page)]
    expect(a).toBeGreaterThan(20)
    expect(Math.abs(a - b)).toBeLessThanOrEqual(2)
  })

  await test.step('round 1: drawing reaches the guesser in order', async () => {
    const tools = r1.drawer.page.getByRole('toolbar', { name: 'Drawing controls' })
    await tools
      .getByRole('button', { name: /brush size$/ })
      .last()
      .click()
    await tools.getByRole('button', { name: 'Select Red color' }).click()
    await drag(r1.drawer, [0.2, 0.5], [0.8, 0.5])
    await tools.getByRole('button', { name: 'Select Blue color' }).click()
    await drag(r1.drawer, [0.5, 0.2], [0.5, 0.8])
    for (const p of players) {
      // The blue stroke was drawn second, so it covers the red one where they cross.
      await expect.poll(async () => isNear((await canvasStats(p.page)).center, BLUE)).toBe(true)
      await expect.poll(async () => (await canvasStats(p.page, RED)).matching).toBeGreaterThan(50)
    }
    expect((await canvasStats(mobile.page)).scale).toBeCloseTo(
      devices['Pixel 7'].deviceScaleFactor,
      1,
    )
  })

  await test.step('round 1: the guesser cannot draw', async () => {
    const before = (await canvasStats(r1.drawer.page)).ink
    const guesserBefore = (await canvasStats(r1.guesser.page)).ink
    await expect(canvasOf(r1.guesser.page)).toHaveAccessibleName('Draw & Guess canvas view')
    await drag(r1.guesser, [0.1, 0.1], [0.9, 0.9])
    await r1.guesser.page.waitForTimeout(500)
    expect((await canvasStats(r1.drawer.page)).ink).toBe(before)
    expect((await canvasStats(r1.guesser.page)).ink).toBe(guesserBefore)
  })

  await test.step('round 1: mobile layout is usable', async () => {
    const width = await mobile.page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth,
      inner: window.innerWidth,
    }))
    expect(width.scroll).toBeLessThanOrEqual(width.inner)
    await expect(canvasOf(mobile.page)).toBeInViewport()
    const box = await canvasOf(mobile.page).boundingBox()
    expect(box?.width ?? 0).toBeGreaterThan(250)
  })

  await test.step('round 1: guesser refresh restores canvas, word stays hidden', async () => {
    await r1.guesser.page.reload()
    await expect(r1.guesser.page.getByLabel(/^Word hint:/)).toBeVisible()
    await expect
      .poll(async () => isNear((await canvasStats(r1.guesser.page)).center, BLUE))
      .toBe(true)
    await expect(r1.guesser.page.getByText(r1.word, { exact: true })).toHaveCount(0)
  })

  await test.step('round 1: guesser loses the network while the drawer draws', async () => {
    r1.guesser.network.cut()
    await expect(r1.guesser.page.getByText(/Reconnecting/).first()).toBeVisible()
    await expect(scores(r1.drawer.page)).toContainText('Offline')
    const tools = r1.drawer.page.getByRole('toolbar', { name: 'Drawing controls' })
    await tools.getByRole('button', { name: 'Select Green color' }).click()
    await drag(r1.drawer, [0.15, 0.25], [0.85, 0.25])
    await expect
      .poll(async () => (await canvasStats(r1.drawer.page, GREEN)).matching)
      .toBeGreaterThan(50)
    r1.guesser.network.restore()
    await expect(r1.guesser.page.getByText(/Reconnecting/)).toHaveCount(0, { timeout: 30_000 })
    await expect(scores(r1.drawer.page)).not.toContainText('Offline')
    await expect
      .poll(async () => (await canvasStats(r1.guesser.page, GREEN)).matching)
      .toBeGreaterThan(50)
    expect(isNear((await canvasStats(r1.guesser.page)).center, BLUE)).toBe(true)
  })

  await test.step('round 1: drawer refresh restores word, canvas and tools', async () => {
    await r1.drawer.page.reload()
    await expect(r1.drawer.page.getByText(r1.word, { exact: true })).toBeVisible()
    await expect(r1.drawer.page.getByRole('toolbar', { name: 'Drawing controls' })).toBeVisible()
    await expect
      .poll(async () => (await canvasStats(r1.drawer.page, GREEN)).matching)
      .toBeGreaterThan(50)
    expect(isNear((await canvasStats(r1.drawer.page)).center, BLUE)).toBe(true)
  })

  await test.step('round 1: wrong guess is chat, right guess scores on both screens', async () => {
    const input = r1.guesser.page.getByRole('textbox', { name: 'Type your guess here…' })
    await input.fill('zzqqzz')
    await input.press('Enter')
    const feed = (p: Page) => p.getByRole('log', { name: 'Guess and chat feed' })
    await expect(feed(r1.drawer.page)).toContainText('zzqqzz')
    await input.fill(r1.word.toUpperCase())
    await input.press('Enter')
    for (const p of players) {
      await expect(p.page.getByText('Round 1 of 2 Complete!')).toBeVisible()
      await expect(p.page.getByText(r1.word, { exact: true }).first()).toBeVisible()
    }
    const game = (await snapshot()).game
    const guesserScore = game?.scores[r1.guesser.guestId] ?? 0
    const drawerScore = game?.scores[r1.drawer.guestId] ?? 0
    expect(guesserScore).toBeGreaterThan(0)
    expect(drawerScore).toBeGreaterThan(0)
    for (const p of players) {
      await expect(scores(p.page)).toContainText(String(guesserScore))
      await expect(scores(p.page)).toContainText(String(drawerScore))
    }
  })

  // ------------------------------------------------------------------ round 2
  const r2 = await chooseWord()
  expect(r2.drawer).toBe(r1.guesser) // drawers rotate

  await test.step('round 2: the other input type draws; the timer ends the round', async () => {
    await drag(r2.drawer, [0.3, 0.3], [0.7, 0.7])
    await expect.poll(async () => (await canvasStats(r2.guesser.page)).ink).toBeGreaterThan(50)
    await expect(r2.guesser.page.getByText(r2.word, { exact: true })).toHaveCount(0)
    // Nobody guesses: the server's DRAWING deadline (30 s) ends the round, not a client timer.
    for (const p of players) {
      await expect(p.page.getByText('Round 2 of 2 Complete!')).toBeVisible({ timeout: 45_000 })
      await expect(p.page.getByText(r2.word, { exact: true }).first()).toBeVisible()
    }
  })

  // ------------------------------------------------------------------ results and rematch
  const firstSession = (await snapshot()).room.sessionId
  await test.step('results: identical final standings on both screens', async () => {
    const standings = async (p: Page) => {
      await expect(p.getByText('Game Finished!')).toBeVisible({ timeout: 30_000 })
      const rows = p.getByRole('table', { name: 'Final Standings' }).getByRole('row')
      return (await rows.allInnerTexts()).map((t) =>
        t
          .replace(/\bYou\b/, '')
          .replace(/\s+/g, ' ')
          .trim(),
      )
    }
    const [a, b] = [await standings(host.page), await standings(mobile.page)]
    expect(a).toHaveLength(2)
    expect(a).toEqual(b)
    expect(a.join(' ')).toContain(nicknameOf(r1.guesser))
  })

  const results = async () => {
    const res = await request.get(`/api/v1/sessions/${firstSession}/results`, {
      headers: authHeaders(host),
    })
    expect(res.ok()).toBe(true)
    return res.text()
  }
  const storedResults = await results()

  await test.step('rematch: back to a clean lobby, second game starts fresh', async () => {
    await mobile.page.getByRole('button', { name: 'Vote for Rematch' }).click()
    await host.page.getByRole('button', { name: 'Start Rematch' }).click()
    for (const p of players) {
      await expect(p.page).toHaveURL(new RegExp(`/rooms/${code}$`))
      await expect(p.page.getByRole('button', { name: 'Ready up' })).toBeVisible()
    }
    await startFromLobby()
    await chooseWord()
    const state = await snapshot()
    expect(state.room.sessionId).not.toBe(firstSession)
    expect(Object.values(state.game?.scores ?? {})).toEqual([0, 0])
    for (const p of players) expect((await canvasStats(p.page)).ink).toBe(0)
    expect(await results()).toBe(storedResults)
  })

  expect(errors).toEqual([])
  for (const p of players) await p.context.close()
})
