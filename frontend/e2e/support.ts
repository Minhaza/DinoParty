import {
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type BrowserContextOptions,
  type Locator,
  type Page,
} from '@playwright/test'

export interface Player {
  nickname: string
  token: string
  guestId: string
  context: BrowserContext
  page: Page
  /** True if this player draws with touch (mobile context), false for mouse. */
  touch: boolean
  network: NetworkSwitch
}

/**
 * Cuts the player's STOMP socket on both ends (like a dropped Wi-Fi) and refuses reconnects until
 * restored. Chromium's offline emulation leaves open WebSockets alive, so it cannot do this.
 */
export interface NetworkSwitch {
  cut(): void
  restore(): void
  /** Every frame the page has sent over its STOMP socket so far (text frames only). */
  sentFrames(): readonly string[]
}

async function routeRealtime(context: BrowserContext): Promise<NetworkSwitch> {
  let blocked = false
  const live = new Set<{ close(): Promise<void> }>()
  const sent: string[] = []
  await context.routeWebSocket(/\/ws$/, (ws) => {
    if (blocked) {
      void ws.close({ code: 4000, reason: 'e2e network cut' })
      return
    }
    const server = ws.connectToServer()
    ws.onMessage((message) => {
      if (typeof message === 'string') sent.push(message)
      server.send(message)
    })
    const pair = {
      close: async () => {
        await ws.close({ code: 4000, reason: 'e2e network cut' })
        await server.close()
      },
    }
    live.add(pair)
    ws.onClose(() => live.delete(pair))
  })
  return {
    cut() {
      blocked = true
      for (const pair of live) void pair.close()
      live.clear()
    },
    restore() {
      blocked = false
    },
    sentFrames: () => sent,
  }
}

export function authHeaders(player: Pick<Player, 'token'>) {
  return { Authorization: `Bearer ${player.token}` }
}

export async function newPlayer(
  browser: Browser,
  request: APIRequestContext,
  nickname: string,
  options: BrowserContextOptions,
): Promise<Player> {
  const res = await request.post('/api/v1/guests', { data: { nickname, avatarKey: 'dino-rex' } })
  expect(res.status(), await res.text()).toBe(201)
  const body = (await res.json()) as { token: string; guest: { id: string } }
  const context = await browser.newContext(options)
  // Same shape the persisted auth store writes; only seeded once so reloads keep the real state.
  await context.addInitScript((token) => {
    if (!localStorage.getItem('dinoparty-auth')) {
      localStorage.setItem(
        'dinoparty-auth',
        JSON.stringify({ state: { token, lastRoomCode: null }, version: 0 }),
      )
    }
  }, body.token)
  const network = await routeRealtime(context)
  const page = await context.newPage()
  return {
    nickname,
    token: body.token,
    guestId: body.guest.id,
    context,
    page,
    touch: options.hasTouch === true,
    network,
  }
}

export function canvasOf(page: Page): Locator {
  return page.getByRole('img', { name: /canvas/i })
}

export interface CanvasStats {
  /** Pixels that differ clearly from the background colour. */
  ink: number
  /** Pixels close to the given RGB colour. */
  matching: number
  /** RGB at the centre of the backing store. */
  center: [number, number, number]
  /** Backing-store width / CSS width (the device pixel ratio actually applied). */
  scale: number
}

/** Reads the canvas backing store; `rgb` selects which colour `matching` counts. */
export async function canvasStats(
  page: Page,
  rgb: [number, number, number] = [0, 0, 0],
): Promise<CanvasStats> {
  return canvasOf(page).evaluate((node, target) => {
    const canvas = node as HTMLCanvasElement
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('no 2d context')
    const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height)
    const bg = [0xff, 0xfd, 0xf8] // CANVAS_BG_COLOR
    let ink = 0
    let matching = 0
    for (let i = 0; i < data.length; i += 4) {
      const dBg =
        Math.abs(data[i] - bg[0]) + Math.abs(data[i + 1] - bg[1]) + Math.abs(data[i + 2] - bg[2])
      if (dBg > 60) ink++
      const dT =
        Math.abs(data[i] - target[0]) +
        Math.abs(data[i + 1] - target[1]) +
        Math.abs(data[i + 2] - target[2])
      if (dT < 60) matching++
    }
    const cx = Math.floor(canvas.width / 2)
    const cy = Math.floor(canvas.height / 2)
    const c = ctx.getImageData(cx, cy, 1, 1).data
    return {
      ink,
      matching,
      center: [c[0], c[1], c[2]] as [number, number, number],
      scale: canvas.width / canvas.getBoundingClientRect().width,
    }
  }, rgb)
}

export const RED: [number, number, number] = [0xef, 0x44, 0x44]
export const BLUE: [number, number, number] = [0x25, 0x63, 0xeb]
export const GREEN: [number, number, number] = [0x16, 0xa3, 0x4a]

export function isNear(rgb: [number, number, number], target: [number, number, number]): boolean {
  return rgb.reduce((sum, v, i) => sum + Math.abs(v - target[i]), 0) < 60
}

/**
 * Drags across the canvas between two points given as fractions of its box, with the mouse or
 * with real touch events (CDP), so the app sees `pointerType` mouse or touch.
 */
export async function drag(
  player: Player,
  from: [number, number],
  to: [number, number],
  steps = 25,
): Promise<void> {
  const box = await canvasOf(player.page).boundingBox()
  if (!box) throw new Error('canvas not visible')
  const at = (t: number) => ({
    x: box.x + box.width * (from[0] + (to[0] - from[0]) * t),
    y: box.y + box.height * (from[1] + (to[1] - from[1]) * t),
  })
  if (!player.touch) {
    const start = at(0)
    await player.page.mouse.move(start.x, start.y)
    await player.page.mouse.down()
    for (let i = 1; i <= steps; i++) {
      const p = at(i / steps)
      await player.page.mouse.move(p.x, p.y)
    }
    await player.page.mouse.up()
    return
  }
  const cdp = await player.context.newCDPSession(player.page)
  const point = (t: number) => {
    const p = at(t)
    return [{ x: p.x, y: p.y, id: 1 }]
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: point(0) })
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: point(i / steps) })
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await cdp.detach()
}

/** Seconds shown by the first visible server countdown. */
export async function secondsLeft(page: Page): Promise<number> {
  const label = await page
    .getByLabel(/^Time remaining:/)
    .first()
    .getAttribute('aria-label')
  const match = /(\d+):(\d{2})/.exec(label ?? '')
  if (!match) throw new Error(`no countdown in "${label}"`)
  return Number(match[1]) * 60 + Number(match[2])
}
