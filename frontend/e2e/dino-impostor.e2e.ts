import { devices, expect, test, type APIRequestContext, type Browser } from '@playwright/test'
import {
  authHeaders,
  canvasInkInBand,
  canvasStats,
  checkA11y,
  drag,
  newPlayer,
  type Player,
} from './support'

const YOUR_TURN = 'Your turn! Add one stroke in your colour.'

/** Waits until a player other than `previous` shows the turn banner (the previous drawer's UI may lag). */
async function findDrawer(players: Player[], previous?: Player): Promise<Player> {
  let found: Player | undefined
  await expect
    .poll(
      async () => {
        for (const p of players) {
          if (p !== previous && (await p.page.getByText(YOUR_TURN).isVisible())) {
            found = p
            return p.nickname
          }
        }
        return undefined
      },
      { timeout: 15_000 },
    )
    .toBeDefined()
  if (!found) throw new Error('No active drawer found')
  return found
}

/** One stroke per player, each on its own row so strokes never overlap. */
async function drawAllTurns(players: Player[], yFor: (turn: number) => number): Promise<void> {
  let previous: Player | undefined
  for (let turn = 0; turn < players.length; turn++) {
    previous = await findDrawer(players, previous)
    await drag(previous, [0.15, yFor(turn)], [0.85, yFor(turn)])
  }
}

/** Waits for every client's role card, then reads who the impostor is and an artist's secret word. */
async function readRoles(players: Player[]): Promise<{ impostor: Player; secretWord: string }> {
  for (const p of players) {
    await expect(p.page.getByTestId('role-card')).toBeVisible({ timeout: 15_000 })
  }
  let impostor: Player | undefined
  let secretWord = ''
  for (const p of players) {
    if (await p.page.getByRole('heading', { name: "You're the impostor" }).isVisible()) {
      impostor = p
    } else if (!secretWord) {
      // The role card is a <dl>: category first, then the word.
      const ddTexts = await p.page.getByTestId('role-card').locator('dd').allInnerTexts()
      secretWord = (ddTexts[1] ?? '').trim()
    }
  }
  if (!impostor) throw new Error('No impostor found')
  expect(secretWord.length).toBeGreaterThan(0)
  return { impostor, secretWord }
}

/** Artists vote for the impostor; the impostor (who cannot vote for themselves) votes for an artist. */
async function castVotes(players: Player[], impostor: Player, skip?: Player): Promise<void> {
  const anyArtist = players.find((p) => p !== impostor)!
  for (const p of players) {
    if (p === skip) continue
    const target = p === impostor ? anyArtist : impostor
    const radio = p.page.getByRole('radio', { name: target.nickname })
    await radio.check()
    await expect(radio).toBeChecked()
  }
}

/**
 * Dino Impostor E2E (P9-08):
 * 1. Full round: role reveal, one stroke each, vote catches impostor, impostor guesses right (+2, GUESSED),
 *    rounds complete to results and rematch. Includes keyboard-only vote on 375px mobile and axe checks on every phase.
 * 2. Refresh mid-turn: drawer reloads mid-turn and completes stroke; watcher reloads and sees stroke & legend.
 * 3. Out-of-turn stroke refused: non-drawer attempts to draw, canvas ink remains 0 on all clients.
 */
test.describe('Dino Impostor', () => {
  const shortestSettings = {
    rounds: 3,
    laps: 1,
    turnSeconds: 10,
    voteSeconds: 30,
    guessSeconds: 15,
    difficulty: 'easy',
  }

  let suitePlayers: {
    host: Player
    mobile: Player
    p3: Player
    p4: Player
    all: Player[]
    others: Player[]
  } | null = null

  async function getPlayers(browser: Browser, request: APIRequestContext) {
    if (!suitePlayers) {
      const num = Math.floor(Math.random() * 9000 + 1000)
      const desktop = { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 860 } }
      const host = await newPlayer(browser, request, `H_di${num}`, desktop)
      const mobile = await newPlayer(browser, request, `M_di${num}`, {
        ...devices['Pixel 7'],
        viewport: { width: 375, height: 667 },
        hasTouch: true,
      })
      const p3 = await newPlayer(browser, request, `P3_di${num}`, desktop)
      const p4 = await newPlayer(browser, request, `P4_di${num}`, desktop)
      suitePlayers = { host, mobile, p3, p4, all: [host, mobile, p3, p4], others: [mobile, p3, p4] }
    }
    return suitePlayers
  }

  test.afterAll(async () => {
    if (suitePlayers) {
      for (const p of suitePlayers.all) await p.context.close()
      suitePlayers = null
    }
  })

  async function createAndStartGame(
    browser: Browser,
    request: APIRequestContext,
  ): Promise<{
    code: string
    host: Player
    mobile: Player
    players: Player[]
    others: Player[]
  }> {
    const { host, mobile, all, others } = await getPlayers(browser, request)

    const created = await request.post('/api/v1/rooms', {
      headers: authHeaders(host),
      data: { gameKey: 'dino-impostor', settings: shortestSettings },
    })
    expect(created.status(), await created.text()).toBe(201)
    const code = ((await created.json()) as { code: string }).code

    for (const p of others) {
      const joined = await request.post(`/api/v1/rooms/${code}/join`, { headers: authHeaders(p) })
      expect(joined.ok(), await joined.text()).toBe(true)
    }

    for (const p of all) {
      await p.page.goto(`/rooms/${code}`)
      await p.page.getByRole('button', { name: 'Ready up' }).click()
      await expect(p.page.getByRole('button', { name: 'Cancel ready' })).toBeVisible()
    }

    await host.page.getByRole('button', { name: 'Start game' }).click()
    for (const p of all) {
      await expect(p.page).toHaveURL(new RegExp(`/rooms/${code}/play$`))
      await expect(
        p.page.getByRole('region', { name: 'Dino Impostor gameplay stage' }),
      ).toBeVisible()
    }

    return { code, host, mobile, players: all, others }
  }

  async function leaveRoom(request: APIRequestContext, code: string, players: Player[]) {
    for (const p of players) {
      await request.post(`/api/v1/rooms/${code}/leave`, { headers: authHeaders(p) }).catch(() => {})
    }
  }

  test('full round, caught impostor guesses right, results and rematch', async ({
    browser,
    request,
  }) => {
    const { code, host, mobile, players, others } = await createAndStartGame(browser, request)
    const errors: string[] = []
    for (const p of players) {
      p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))
    }

    // --- PHASE 1: ROLE_REVEAL ---
    await test.step('Role reveal: inspect roles and word; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'Check your secret' })).toBeVisible()
      await checkA11y(host.page, 'Dino Impostor', 'ROLE_REVEAL')
    })

    const { impostor, secretWord } = await readRoles(players)

    // --- PHASE 2: DRAWING ---
    await test.step('Drawing turns: every player draws one stroke; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'One stroke each' })).toBeVisible({
        timeout: 10_000,
      })
      await checkA11y(host.page, 'Dino Impostor', 'DRAWING')

      // 4 players, laps = 1 -> exactly 4 turns
      await drawAllTurns(players, (turn) => 0.2 + 0.15 * turn)
    })

    // --- PHASE 3: VOTE ---
    await test.step('Vote: keyboard-only vote on 375px mobile, everyone votes impostor; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'Find the impostor' })).toBeVisible({
        timeout: 15_000,
      })
      await checkA11y(host.page, 'Dino Impostor', 'VOTE')

      // Mobile player (375px width) votes by keyboard only (focus + Space; Tab order is not exercised).
      // If mobile is the impostor, it votes for an artist; otherwise for the impostor.
      const anyArtist = players.find((p) => p !== impostor)!
      const mobileTarget = mobile === impostor ? anyArtist : impostor
      const mobileRadio = mobile.page.getByRole('radio', { name: mobileTarget.nickname })
      await mobileRadio.focus()
      await mobile.page.keyboard.press('Space')
      await expect(mobileRadio).toBeChecked()

      await castVotes(players, impostor, mobile)
    })

    // --- PHASE 4: GUESS ---
    await test.step('Guess: caught impostor enters word; a11y check', async () => {
      await expect(
        host.page.getByRole('heading', { name: 'Last chance for the impostor' }),
      ).toBeVisible({ timeout: 25_000 })
      await checkA11y(impostor.page, 'Dino Impostor', 'GUESS')

      // Non-impostor view
      const nonImpostor = players.find((p) => p !== impostor)!
      await expect(nonImpostor.page.getByText('The impostor is guessing the word…')).toBeVisible()

      // Impostor submits correct word
      await expect(
        impostor.page.getByRole('heading', { name: 'You were caught — name the word!' }),
      ).toBeVisible()
      await impostor.page.getByLabel('Your guess').fill(secretWord)
      await impostor.page.getByRole('button', { name: 'Lock it in' }).click()
    })

    // --- PHASE 5: ROUND_END ---
    await test.step('Round reveal: shows GUESSED and impostor gets +2; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'The big reveal' })).toBeVisible({
        timeout: 15_000,
      })
      await checkA11y(host.page, 'Dino Impostor', 'ROUND_END')

      await expect(
        host.page.getByRole('heading', { name: 'Caught — but they cracked the word!' }),
      ).toBeVisible()
      await expect(host.page.getByText(impostor.nickname).first()).toBeVisible()
      await expect(host.page.getByText(secretWord).first()).toBeVisible()
      // Score delta +2 shown in the table for impostor
      await expect(host.page.getByRole('table').getByText('+2')).toBeVisible()
    })

    // --- CARRY ON TO RESULTS & REMATCH ---
    await test.step('Play remaining rounds to reach Results and Rematch', async () => {
      // Round 2 and Round 3: play briskly
      for (let r = 2; r <= 3; r++) {
        // Role reveal comes first; read the roles before drawing starts
        const roundRoles = await readRoles(players)
        await expect(host.page.getByRole('heading', { name: 'One stroke each' })).toBeVisible({
          timeout: 20_000,
        })
        await drawAllTurns(players, () => 0.4)

        // Vote phase: everyone votes for the impostor, so the round is always CAUGHT and a guess follows
        await expect(host.page.getByRole('heading', { name: 'Find the impostor' })).toBeVisible({
          timeout: 15_000,
        })
        await castVotes(players, roundRoles.impostor)

        await expect(
          host.page.getByRole('heading', { name: 'Last chance for the impostor' }),
        ).toBeVisible({ timeout: 25_000 })
        await roundRoles.impostor.page.getByLabel('Your guess').fill('wrongguess')
        await roundRoles.impostor.page.getByRole('button', { name: 'Lock it in' }).click()

        await expect(host.page.getByRole('heading', { name: 'The big reveal' })).toBeVisible({
          timeout: 20_000,
        })
      }

      // After round 3: Game finishes and results screen appears
      await expect(host.page.getByRole('button', { name: 'Start Rematch' })).toBeVisible({
        timeout: 25_000,
      })

      // Rematch flow
      for (const p of others) {
        await p.page.getByRole('button', { name: 'Vote for Rematch' }).click()
      }
      await host.page.getByRole('button', { name: 'Start Rematch' }).click()

      for (const p of players) {
        await expect(p.page).toHaveURL(new RegExp(`/rooms/${code}$`))
        await expect(p.page.getByRole('button', { name: 'Ready up' })).toBeVisible()
      }
    })

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })

  test('refresh mid-turn: drawer recovers turnStroke and watcher sees strokes', async ({
    browser,
    request,
  }) => {
    const { code, host, players } = await createAndStartGame(browser, request)
    const errors: string[] = []
    for (const p of players) {
      p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))
    }

    // Wait for drawing turn
    await expect(host.page.getByRole('heading', { name: 'One stroke each' })).toBeVisible({
      timeout: 10_000,
    })

    const drawer = await findDrawer(players)

    const watcher = players.find((p) => p !== drawer)!

    // Drawer reloads mid-turn
    await drawer.page.reload()
    await expect(
      drawer.page.getByRole('region', { name: 'Dino Impostor gameplay stage' }),
    ).toBeVisible()
    await expect(drawer.page.getByText(YOUR_TURN)).toBeVisible()

    // Drawer draws their one stroke
    await drag(drawer, [0.2, 0.5], [0.8, 0.5])

    // Watcher reloads and sees stroke & legend
    await watcher.page.reload()
    await expect(
      watcher.page.getByRole('region', { name: 'Dino Impostor gameplay stage' }),
    ).toBeVisible()
    await expect(watcher.page.getByRole('region', { name: 'Turn order' })).toBeVisible()
    await expect(watcher.page.getByRole('region', { name: 'Stroke colours' })).toBeVisible()

    const stats = await canvasStats(watcher.page)
    expect(stats.ink).toBeGreaterThan(0)

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })

  test('out-of-turn stroke refused: canvas remains unchanged', async ({ browser, request }) => {
    const { code, host, players } = await createAndStartGame(browser, request)
    const errors: string[] = []
    for (const p of players) {
      p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))
    }

    await expect(host.page.getByRole('heading', { name: 'One stroke each' })).toBeVisible({
      timeout: 10_000,
    })

    const drawer = await findDrawer(players)
    const activeDrawer = drawer
    const nonDrawer = players.find((p) => p !== activeDrawer)!

    // Verify initial canvas is blank (ink = 0)
    const initialStats = await canvasStats(nonDrawer.page)
    expect(initialStats.ink).toBe(0)

    // Non-drawer attempts to drag on canvas (row 0.4)
    await drag(nonDrawer, [0.2, 0.4], [0.8, 0.4])

    // Now the real drawer draws a valid stroke (row 0.6). Messages are processed in order, so once it
    // reaches the non-drawer, any accepted out-of-turn stroke would already be on that canvas too.
    await drag(activeDrawer, [0.2, 0.6], [0.8, 0.6])
    await expect
      .poll(async () => (await canvasInkInBand(nonDrawer.page, 0.55, 0.65)).ink, {
        timeout: 10_000,
      })
      .toBeGreaterThan(0)

    // The refused stroke left nothing on either canvas, even after the server round trip
    for (const p of [nonDrawer, activeDrawer]) {
      expect((await canvasInkInBand(p.page, 0.35, 0.45)).ink).toBe(0)
    }

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })
})
