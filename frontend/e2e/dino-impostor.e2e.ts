import { devices, expect, test, type APIRequestContext, type Browser } from '@playwright/test'
import {
  authHeaders,
  canvasStats,
  checkA11y,
  drag,
  newPlayer,
  type AxePhaseCheck,
  type Player,
} from './support'

export const a11yLog: AxePhaseCheck[] = []

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
      await checkA11y(host.page, 'Dino Impostor', 'ROLE_REVEAL', a11yLog)
    })

    // Determine the impostor and secret word from the UI
    let impostor: Player | undefined
    let secretWord = ''
    for (const p of players) {
      const isImpostor = await p.page
        .getByRole('heading', { name: "You're the impostor" })
        .isVisible()
      if (isImpostor) {
        impostor = p
      } else if (!secretWord) {
        const card = p.page.getByTestId('role-card')
        const ddTexts = await card.locator('dd').allInnerTexts()
        secretWord = (ddTexts[1] ?? '').trim()
      }
    }
    expect(impostor).toBeDefined()
    expect(secretWord.length).toBeGreaterThan(0)

    // --- PHASE 2: DRAWING ---
    await test.step('Drawing turns: every player draws one stroke; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'One stroke each' })).toBeVisible({
        timeout: 10_000,
      })
      await checkA11y(host.page, 'Dino Impostor', 'DRAWING', a11yLog)

      // 4 players, laps = 1 -> exactly 4 turns
      for (let turn = 0; turn < players.length; turn++) {
        let activeDrawer: Player | undefined
        await expect
          .poll(
            async () => {
              for (const p of players) {
                if (
                  await p.page.getByText('Your turn! Add one stroke in your colour.').isVisible()
                ) {
                  activeDrawer = p
                  return p.nickname
                }
              }
              return undefined
            },
            { timeout: 15_000 },
          )
          .toBeDefined()

        if (!activeDrawer) throw new Error(`No active drawer for turn ${turn}`)
        const yFrac = 0.2 + 0.15 * turn
        await drag(activeDrawer, [0.15, yFrac], [0.85, yFrac])
      }
    })

    // --- PHASE 3: VOTE ---
    await test.step('Vote: keyboard-only vote on 375px mobile, everyone votes impostor; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'Find the impostor' })).toBeVisible({
        timeout: 15_000,
      })
      await checkA11y(host.page, 'Dino Impostor', 'VOTE', a11yLog)

      // Mobile player (375px width) performs a keyboard-only vote.
      // If mobile is impostor, mobile votes for any artist; otherwise mobile votes for impostor.
      const anyArtist = players.find((p) => p !== impostor)!
      const mobileTarget = mobile === impostor ? anyArtist : impostor!
      const mobileRadio = mobile.page.getByRole('radio', { name: mobileTarget.nickname })
      await mobileRadio.focus()
      await mobile.page.keyboard.press('Space')
      await expect(mobileRadio).toBeChecked()

      // Other players vote
      for (const p of players) {
        if (p === mobile) continue
        if (p === impostor) {
          // Impostor cannot vote for themselves; votes for an artist
          const radio = p.page.getByRole('radio', { name: anyArtist.nickname })
          await radio.check()
          await expect(radio).toBeChecked()
        } else {
          // Real artists vote to catch the impostor
          const radio = p.page.getByRole('radio', { name: impostor!.nickname })
          await radio.check()
          await expect(radio).toBeChecked()
        }
      }
    })

    // --- PHASE 4: GUESS ---
    await test.step('Guess: caught impostor enters word; a11y check', async () => {
      await expect(
        host.page.getByRole('heading', { name: 'Last chance for the impostor' }),
      ).toBeVisible({ timeout: 25_000 })
      await checkA11y(impostor!.page, 'Dino Impostor', 'GUESS', a11yLog)

      // Non-impostor view
      const nonImpostor = players.find((p) => p !== impostor)!
      await expect(nonImpostor.page.getByText('The impostor is guessing the word…')).toBeVisible()

      // Impostor submits correct word
      await expect(
        impostor!.page.getByRole('heading', { name: 'You were caught — name the word!' }),
      ).toBeVisible()
      await impostor!.page.getByLabel('Your guess').fill(secretWord)
      await impostor!.page.getByRole('button', { name: 'Lock it in' }).click()
    })

    // --- PHASE 5: ROUND_END ---
    await test.step('Round reveal: shows GUESSED and impostor gets +2; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: 'The big reveal' })).toBeVisible({
        timeout: 15_000,
      })
      await checkA11y(host.page, 'Dino Impostor', 'ROUND_END', a11yLog)

      await expect(
        host.page.getByRole('heading', { name: 'Caught — but they cracked the word!' }),
      ).toBeVisible()
      await expect(host.page.getByText(impostor!.nickname).first()).toBeVisible()
      await expect(host.page.getByText(secretWord).first()).toBeVisible()
      // Score delta +2 shown in the table for impostor
      await expect(host.page.getByRole('table').getByText('+2')).toBeVisible()
    })

    // --- CARRY ON TO RESULTS & REMATCH ---
    await test.step('Play remaining rounds to reach Results and Rematch', async () => {
      // Round 2 and Round 3: play briskly
      for (let r = 2; r <= 3; r++) {
        // Wait for drawing
        await expect(host.page.getByRole('heading', { name: 'One stroke each' })).toBeVisible({
          timeout: 20_000,
        })
        for (let turn = 0; turn < players.length; turn++) {
          let active: Player | undefined
          await expect
            .poll(
              async () => {
                for (const p of players) {
                  if (
                    await p.page.getByText('Your turn! Add one stroke in your colour.').isVisible()
                  ) {
                    active = p
                    return p.nickname
                  }
                }
                return undefined
              },
              { timeout: 15_000 },
            )
            .toBeDefined()
          if (active) {
            await drag(active, [0.2, 0.4], [0.8, 0.4])
          }
        }

        // Vote phase
        await expect(host.page.getByRole('heading', { name: 'Find the impostor' })).toBeVisible({
          timeout: 15_000,
        })
        for (const p of players) {
          const firstRadio = p.page.getByRole('radio').first()
          if (await firstRadio.isVisible()) await firstRadio.check()
        }

        // Guess or Round end
        await expect(
          host.page
            .getByRole('heading', { name: 'Last chance for the impostor' })
            .or(host.page.getByRole('heading', { name: 'The big reveal' })),
        ).toBeVisible({ timeout: 25_000 })

        // If in guess phase, submit guess or wait
        for (const p of players) {
          const guessBtn = p.page.getByRole('button', { name: 'Lock it in' })
          if (await guessBtn.isVisible()) {
            await p.page.getByLabel('Your guess').fill('wrongguess')
            await guessBtn.click()
            break
          }
        }

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

    let drawer: Player | undefined
    await expect
      .poll(
        async () => {
          for (const p of players) {
            if (await p.page.getByText('Your turn! Add one stroke in your colour.').isVisible()) {
              drawer = p
              return p.nickname
            }
          }
          return undefined
        },
        { timeout: 15_000 },
      )
      .toBeDefined()
    if (!drawer) throw new Error('No drawer found')

    const watcher = players.find((p) => p !== drawer)!

    // Drawer reloads mid-turn
    await drawer.page.reload()
    await expect(
      drawer.page.getByRole('region', { name: 'Dino Impostor gameplay stage' }),
    ).toBeVisible()
    await expect(drawer.page.getByText('Your turn! Add one stroke in your colour.')).toBeVisible()

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

    let drawer: Player | undefined
    await expect
      .poll(
        async () => {
          for (const p of players) {
            if (await p.page.getByText('Your turn! Add one stroke in your colour.').isVisible()) {
              drawer = p
              return p.nickname
            }
          }
          return undefined
        },
        { timeout: 15_000 },
      )
      .toBeDefined()
    if (!drawer) throw new Error('No drawer found')
    const activeDrawer = drawer
    const nonDrawer = players.find((p) => p !== activeDrawer)!

    // Verify initial canvas is blank (ink = 0)
    const initialStats = await canvasStats(nonDrawer.page)
    expect(initialStats.ink).toBe(0)

    // Non-drawer attempts to drag on canvas
    await drag(nonDrawer, [0.2, 0.4], [0.8, 0.4])

    // Canvas ink remains 0 on both non-drawer and drawer
    const afterNonDrawerStats = await canvasStats(nonDrawer.page)
    const afterDrawerStats = await canvasStats(activeDrawer.page)
    expect(afterNonDrawerStats.ink).toBe(0)
    expect(afterDrawerStats.ink).toBe(0)

    // Now real drawer draws a valid stroke
    await drag(activeDrawer, [0.2, 0.6], [0.8, 0.6])
    await expect
      .poll(async () => (await canvasStats(activeDrawer.page)).ink, { timeout: 10_000 })
      .toBeGreaterThan(0)

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })
})
