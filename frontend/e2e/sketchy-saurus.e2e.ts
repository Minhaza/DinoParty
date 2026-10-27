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
 * Sketchy Saurus E2E (P9-08):
 * 1. impostorKnows off, unawareGuess off:
 *    - All role cards show words and look identical.
 *    - Everyone draws & submits; gallery shows 4 drawings.
 *    - Voting catches impostor; no guess phase, reveal shows decoy word with CAUGHT outcome.
 * 2. impostorKnows off, unawareGuess on:
 *    - Caught unaware impostor sees "you were the impostor" and guess input; guesses; reveal shows result.
 * 3. Refresh mid-sketch:
 *    - Player reloads during SKETCH, draft is restored, can still submit.
 */
test.describe('Sketchy Saurus', () => {
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
      const host = await newPlayer(browser, request, `H_ss${num}`, desktop)
      const mobile = await newPlayer(browser, request, `M_ss${num}`, {
        ...devices['Pixel 7'],
        viewport: { width: 375, height: 667 },
        hasTouch: true,
      })
      const p3 = await newPlayer(browser, request, `P3_ss${num}`, desktop)
      const p4 = await newPlayer(browser, request, `P4_ss${num}`, desktop)
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
    settingsOverrides: { impostorKnows: boolean; unawareGuess: boolean },
  ): Promise<{
    code: string
    host: Player
    mobile: Player
    players: Player[]
    others: Player[]
  }> {
    const { host, mobile, all, others } = await getPlayers(browser, request)

    const settings = {
      rounds: 3,
      sketchSeconds: 30,
      voteSeconds: 30,
      guessSeconds: 15,
      difficulty: 'easy',
      ...settingsOverrides,
    }

    const created = await request.post('/api/v1/rooms', {
      headers: authHeaders(host),
      data: { gameKey: 'sketchy-saurus', settings },
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
        p.page.getByRole('region', { name: 'Sketchy Saurus gameplay stage' }),
      ).toBeVisible()
    }

    return { code, host, mobile, players: all, others }
  }

  async function leaveRoom(request: APIRequestContext, code: string, players: Player[]) {
    for (const p of players) {
      await request.post(`/api/v1/rooms/${code}/leave`, { headers: authHeaders(p) }).catch(() => {})
    }
  }

  test('impostorKnows off, unawareGuess off: caught directly to reveal with decoyWord', async ({
    browser,
    request,
  }) => {
    const { code, host, players } = await createAndStartGame(browser, request, {
      impostorKnows: false,
      unawareGuess: false,
    })
    const errors: string[] = []
    for (const p of players) {
      p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))
    }

    // --- PHASE 1: ROLE_REVEAL ---
    await test.step('Role reveal: every player sees an artist card with a word; a11y check', async () => {
      await expect(host.page.getByRole('heading', { name: "You're an artist" })).toBeVisible()
      await checkA11y(host.page, 'Sketchy Saurus', 'ROLE_REVEAL', a11yLog)

      for (const p of players) {
        await expect(p.page.getByRole('heading', { name: "You're an artist" })).toBeVisible()
      }
    })

    // Inspect cards to identify realWord, decoyWord and the unaware impostor
    const wordByPlayer = new Map<Player, string>()
    const wordCounts = new Map<string, number>()
    for (const p of players) {
      const card = p.page.getByTestId('role-card')
      const ddTexts = await card.locator('dd').allInnerTexts()
      const word = (ddTexts[1] ?? '').trim()
      expect(word.length).toBeGreaterThan(0)
      wordByPlayer.set(p, word)
      wordCounts.set(word, (wordCounts.get(word) ?? 0) + 1)
    }

    // Real word appears 3 times, decoy word appears 1 time
    let realWord = ''
    let decoyWord = ''
    for (const [w, count] of wordCounts.entries()) {
      if (count === 3) realWord = w
      if (count === 1) decoyWord = w
    }
    expect(realWord.length).toBeGreaterThan(0)
    expect(decoyWord.length).toBeGreaterThan(0)

    let unawareImpostor: Player | undefined
    for (const [p, w] of wordByPlayer.entries()) {
      if (w === decoyWord) unawareImpostor = p
    }
    expect(unawareImpostor).toBeDefined()

    // --- PHASE 2: SKETCH ---
    await test.step('Sketch phase: everyone draws and submits; a11y check', async () => {
      await expect(host.page.getByRole('button', { name: 'Submit sketch' })).toBeVisible({
        timeout: 10_000,
      })
      await checkA11y(host.page, 'Sketchy Saurus', 'SKETCH', a11yLog)

      for (const p of players) {
        await drag(p, [0.2, 0.4], [0.8, 0.4])
        await p.page.getByRole('button', { name: 'Submit sketch' }).click()
        await expect(
          p.page
            .getByText(/Waiting for other dinos|Submitting/i)
            .or(p.page.getByText('The Gallery · Spot the Impostor!')),
        ).toBeVisible()
      }
    })

    // --- PHASE 3: GALLERY ---
    await test.step('Gallery phase: 4 drawings shown, artists vote for impostor; a11y check', async () => {
      await expect(host.page.getByText('The Gallery · Spot the Impostor!')).toBeVisible({
        timeout: 15_000,
      })
      await checkA11y(host.page, 'Sketchy Saurus', 'GALLERY', a11yLog)

      // Gallery shows 4 drawings
      const drawings = host.page.getByRole('img', { name: /^Drawing by /i })
      await expect(drawings).toHaveCount(4)

      // Artists vote for the unaware impostor
      const anyArtist = players.find((p) => p !== unawareImpostor)!
      for (const p of players) {
        if (p === unawareImpostor) {
          // Impostor votes for an artist
          const radio = p.page.getByRole('radio', { name: anyArtist.nickname })
          await radio.check()
          await expect(radio).toBeChecked()
        } else {
          // Artists vote for the impostor
          const radio = p.page.getByRole('radio', { name: unawareImpostor!.nickname })
          await radio.check()
          await expect(radio).toBeChecked()
        }
      }
    })

    // --- PHASE 4: DIRECT TO ROUND_END (NO GUESS) ---
    await test.step('Reveal phase: CAUGHT outcome, decoy word shown, no guess input; a11y check', async () => {
      // With unawareGuess off, NO guess heading/input appears
      await expect(host.page.getByRole('heading', { name: 'Impostor caught!' })).toBeVisible({
        timeout: 25_000,
      })
      await checkA11y(host.page, 'Sketchy Saurus', 'ROUND_END', a11yLog)

      // The reveal shows the real word and the decoy word
      await expect(host.page.getByText("The impostor's decoy word")).toBeVisible()
      await expect(host.page.getByText(decoyWord).first()).toBeVisible()
      await expect(host.page.getByText(realWord).first()).toBeVisible()
      await expect(host.page.getByText(unawareImpostor!.nickname).first()).toBeVisible()

      // Confirm no guess was taken
      await expect(host.page.getByText('No guess').first()).toBeVisible()
    })

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })

  test('impostorKnows off, unawareGuess on: caught impostor gets role notice, guesses, and reveal shows result', async ({
    browser,
    request,
  }) => {
    const { code, host, players } = await createAndStartGame(browser, request, {
      impostorKnows: false,
      unawareGuess: true,
    })
    const errors: string[] = []
    for (const p of players) {
      p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))
    }

    // Role reveal
    await expect(host.page.getByRole('heading', { name: "You're an artist" })).toBeVisible()

    // Find realWord, decoyWord and unawareImpostor
    const wordByPlayer = new Map<Player, string>()
    const wordCounts = new Map<string, number>()
    for (const p of players) {
      const card = p.page.getByTestId('role-card')
      const ddTexts = await card.locator('dd').allInnerTexts()
      const word = (ddTexts[1] ?? '').trim()
      wordByPlayer.set(p, word)
      wordCounts.set(word, (wordCounts.get(word) ?? 0) + 1)
    }

    let realWord = ''
    let decoyWord = ''
    for (const [w, count] of wordCounts.entries()) {
      if (count === 3) realWord = w
      if (count === 1) decoyWord = w
    }

    let unawareImpostor: Player | undefined
    for (const [p, w] of wordByPlayer.entries()) {
      if (w === decoyWord) unawareImpostor = p
    }
    expect(unawareImpostor).toBeDefined()

    // Sketch phase: everyone draws and submits
    await expect(host.page.getByRole('button', { name: 'Submit sketch' })).toBeVisible({
      timeout: 10_000,
    })
    for (const p of players) {
      await drag(p, [0.2, 0.4], [0.8, 0.4])
      await p.page.getByRole('button', { name: 'Submit sketch' }).click()
    }

    // Gallery phase: vote to catch the impostor
    await expect(host.page.getByText('The Gallery · Spot the Impostor!')).toBeVisible({
      timeout: 15_000,
    })
    const anyArtist = players.find((p) => p !== unawareImpostor)!
    for (const p of players) {
      if (p === unawareImpostor) {
        const radio = p.page.getByRole('radio', { name: anyArtist.nickname })
        await radio.check()
      } else {
        const radio = p.page.getByRole('radio', { name: unawareImpostor!.nickname })
        await radio.check()
      }
    }

    // Guess phase: caught unaware impostor receives role notice and guess input
    await test.step('Guess phase: unaware impostor learns role, guesses; a11y check', async () => {
      await expect(
        unawareImpostor!.page.getByRole('heading', { name: 'You were the impostor!' }),
      ).toBeVisible({ timeout: 25_000 })
      await checkA11y(unawareImpostor!.page, 'Sketchy Saurus', 'GUESS', a11yLog)

      await expect(
        unawareImpostor!.page.getByRole('heading', { name: 'You were caught — name the word!' }),
      ).toBeVisible()

      // Impostor guesses the real word
      await unawareImpostor!.page.getByLabel('Your guess').fill(realWord)
      await unawareImpostor!.page.getByRole('button', { name: 'Lock it in' }).click()
    })

    // Round reveal: shows GUESSED outcome and the decoy word
    await expect(
      host.page.getByRole('heading', { name: 'Caught — but they cracked the word!' }),
    ).toBeVisible({ timeout: 15_000 })
    await expect(host.page.getByText("The impostor's decoy word")).toBeVisible()
    await expect(host.page.getByText(decoyWord).first()).toBeVisible()
    await expect(host.page.getByText(realWord).first()).toBeVisible()

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })

  test('refresh mid-sketch: draft restored and player can submit', async ({ browser, request }) => {
    const { code, host, players } = await createAndStartGame(browser, request, {
      impostorKnows: false,
      unawareGuess: false,
    })
    const errors: string[] = []
    for (const p of players) {
      p.page.on('pageerror', (e) => errors.push(`${p.nickname}: ${e.message}`))
    }

    // Wait for sketch phase
    await expect(host.page.getByRole('button', { name: 'Submit sketch' })).toBeVisible({
      timeout: 10_000,
    })

    // Host draws a stroke
    await drag(host, [0.2, 0.4], [0.8, 0.4])
    const statsBefore = await canvasStats(host.page)
    expect(statsBefore.ink).toBeGreaterThan(0)

    // Host reloads page mid-sketch
    await host.page.reload()
    await expect(
      host.page.getByRole('region', { name: 'Sketchy Saurus gameplay stage' }),
    ).toBeVisible()
    await expect(host.page.getByRole('button', { name: 'Submit sketch' })).toBeVisible()

    // Verify draft is restored on canvas
    const statsAfter = await canvasStats(host.page)
    expect(statsAfter.ink).toBeGreaterThan(0)

    // Host can still submit the draft
    await host.page.getByRole('button', { name: 'Submit sketch' }).click()
    await expect(
      host.page
        .getByText(/Waiting for other dinos|Submitting/i)
        .or(host.page.getByText('The Gallery · Spot the Impostor!')),
    ).toBeVisible()

    expect(errors).toEqual([])
    await leaveRoom(request, code, players)
  })
})
