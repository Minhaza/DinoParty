import { DRAW_GUESS_DETAIL, gameHandlers, gameSummary, mockApi, problem } from '@/test/mockApi'

import { getGame, listGames } from './games'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('games API (api-contracts §2.6)', () => {
  it('lists every game, disabled ones included, without authentication', async () => {
    const disabled = { ...gameSummary(DRAW_GUESS_DETAIL), key: 'old-game', enabled: false }
    const calls = mockApi({
      'GET /games': { status: 200, body: [gameSummary(DRAW_GUESS_DETAIL), disabled] },
    })

    const games = await listGames()

    expect(games.map((g) => [g.key, g.enabled])).toEqual([
      ['draw-guess', true],
      ['old-game', false],
    ])
    expect(calls[0]?.path).toBe('/games')
  })

  it('reads a detail with its settings schema and defaults', async () => {
    mockApi(gameHandlers())

    const detail = await getGame('telephone')

    expect(detail.minPlayers).toBe(4)
    expect(detail.settingsSchema.map((f) => f.key)).toEqual([
      'promptSeconds',
      'drawSeconds',
      'describeSeconds',
      'entriesPerChain',
      'allowSkipTimeout',
    ])
    expect(detail.defaultSettings).toMatchObject({ entriesPerChain: 0 })
  })

  it('encodes the key in the path', async () => {
    const calls = mockApi({ 'GET /games/a%2Fb': problem(404, 'GAME_NOT_FOUND') })

    await expect(getGame('a/b')).rejects.toMatchObject({ status: 404, code: 'GAME_NOT_FOUND' })
    expect(calls[0]?.path).toBe('/games/a%2Fb')
  })

  it('drops setting fields of a type this client does not know instead of failing', async () => {
    mockApi({
      'GET /games/draw-guess': {
        status: 200,
        body: {
          ...DRAW_GUESS_DETAIL,
          settingsSchema: [
            ...DRAW_GUESS_DETAIL.settingsSchema,
            { key: 'colour', label: 'Colour', type: 'rgb', default: '#fff' },
          ],
        },
      },
    })

    const detail = await getGame('draw-guess')

    expect(detail.settingsSchema.some((f) => f.key === 'colour')).toBe(false)
    expect(detail.settingsSchema).toHaveLength(DRAW_GUESS_DETAIL.settingsSchema.length)
  })

  it('rejects malformed bodies as a bad gateway', async () => {
    mockApi({
      'GET /games': { status: 200, body: [{ key: 'x' }] },
      'GET /games/x': { status: 200, body: { key: 'x', name: 'X' } },
    })

    await expect(listGames()).rejects.toMatchObject({ status: 502 })
    await expect(getGame('x')).rejects.toMatchObject({ status: 502 })
  })
})
