// DEV-ONLY fixture preview for the Dino Impostor and Sketchy Saurus screens (P9-05 / P9-06 browser check). Mounted at
// `/__dev/impostor` only when `import.meta.env.DEV` is true (app/routes.tsx) and linked from nowhere;
// the production build contains neither this page nor the fixtures it imports.

import * as React from 'react'

import type { ClientCommand, GameSnapshot, PlayerDto, RoomDto } from '@/api/types'
import type {
  DinoImpostorSnapshot,
  ImpostorOutcome,
  ImpostorVotePayload,
} from '@/features/impostor/common/types'
import { DinoImpostorStage } from '@/features/impostor/dino/DinoImpostorStage'
import { useDinoImpostorStore } from '@/features/impostor/dino/dinoImpostorStore'
import * as fx from '@/features/impostor/dino/fixtures'
import * as sfx from '@/features/impostor/sketchy/fixtures'
import { SketchySaurusStage } from '@/features/impostor/sketchy/SketchySaurusStage'
import { useSketchySaurusStore } from '@/features/impostor/sketchy/sketchySaurusStore'
import type { SketchySaurusSnapshot } from '@/features/impostor/sketchy/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import { useRoomStore } from '@/stores/roomStore'

interface Scenario {
  id: string
  label: string
  gameKey: 'dino-impostor' | 'sketchy-saurus'
  snapshot: DinoImpostorSnapshot | SketchySaurusSnapshot
  viewer: string
  players?: PlayerDto[]
}

const OUTCOMES: ImpostorOutcome[] = ['ESCAPED', 'GUESSED', 'CAUGHT', 'IMPOSTOR_LEFT']

const SCENARIOS: Scenario[] = [
  // Dino Impostor scenarios
  {
    id: 'role-artist',
    label: 'DI · Role reveal · artist',
    gameKey: 'dino-impostor',
    snapshot: fx.roleRevealArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'role-impostor',
    label: 'DI · Role reveal · impostor',
    gameKey: 'dino-impostor',
    snapshot: fx.roleRevealImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'role-spectator',
    label: 'DI · Role reveal · spectator',
    gameKey: 'dino-impostor',
    snapshot: fx.roleRevealSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  {
    id: 'draw-mine',
    label: 'DI · Drawing · my turn (artist)',
    gameKey: 'dino-impostor',
    snapshot: fx.drawingMyTurnArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'draw-other',
    label: 'DI · Drawing · watching (artist)',
    gameKey: 'dino-impostor',
    snapshot: fx.drawingOtherTurnArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'draw-impostor',
    label: 'DI · Drawing · watching (impostor)',
    gameKey: 'dino-impostor',
    snapshot: fx.drawingOtherTurnImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'draw-spectator',
    label: 'DI · Drawing · spectator',
    gameKey: 'dino-impostor',
    snapshot: fx.drawingSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  {
    id: 'draw-full',
    label: 'DI · Drawing · full canvas, 10 players',
    gameKey: 'dino-impostor',
    snapshot: fx.drawingFullCanvas,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'draw-four',
    label: 'DI · Drawing · 4 players, first turn',
    gameKey: 'dino-impostor',
    snapshot: fx.drawingFourPlayers,
    viewer: 'di-g2',
    players: fx.DI_PLAYERS_4,
  },
  {
    id: 'vote-artist',
    label: 'DI · Vote · artist (voted)',
    gameKey: 'dino-impostor',
    snapshot: fx.voteArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'vote-impostor',
    label: 'DI · Vote · impostor',
    gameKey: 'dino-impostor',
    snapshot: fx.voteImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'vote-spectator',
    label: 'DI · Vote · spectator',
    gameKey: 'dino-impostor',
    snapshot: fx.voteSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  {
    id: 'guess-impostor',
    label: 'DI · Guess · caught impostor',
    gameKey: 'dino-impostor',
    snapshot: fx.guessAsImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'guess-artist',
    label: 'DI · Guess · artist waiting',
    gameKey: 'dino-impostor',
    snapshot: fx.guessAsArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'guess-spectator',
    label: 'DI · Guess · spectator',
    gameKey: 'dino-impostor',
    snapshot: fx.guessSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  ...OUTCOMES.map((o): Scenario => ({
    id: `end-${o}`,
    label: `DI · Round end · ${o}`,
    gameKey: 'dino-impostor',
    snapshot: fx.roundEndSnapshot(o),
    viewer: fx.VIEWER_ARTIST,
  })),
  // Sketchy Saurus scenarios
  {
    id: 'ss-role-artist',
    label: 'SS · Role reveal · artist',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROLE_REVEAL', sfx.ARTIST_ROLE),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-role-aware-impostor',
    label: 'SS · Role reveal · aware impostor',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROLE_REVEAL', sfx.AWARE_IMPOSTOR_ROLE),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-role-unaware-impostor',
    label: 'SS · Role reveal · unaware impostor (decoy)',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROLE_REVEAL', sfx.UNAWARE_IMPOSTOR_ROLE),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-role-spectator',
    label: 'SS · Role reveal · spectator',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROLE_REVEAL', null),
    viewer: 'ss-spectator',
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-sketch-artist',
    label: 'SS · Sketch · artist drafting',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('SKETCH', sfx.ARTIST_ROLE),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-sketch-unaware-impostor',
    label: 'SS · Sketch · unaware impostor (decoy)',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('SKETCH', sfx.UNAWARE_IMPOSTOR_ROLE),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-sketch-submitted',
    label: 'SS · Sketch · submitted waiting',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('SKETCH', sfx.ARTIST_ROLE, {
      submitted: true,
      submittedGuestIds: ['ss-g1', 'ss-g3'],
    }),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-sketch-aware-impostor',
    label: 'SS · Sketch · aware impostor',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('SKETCH', sfx.AWARE_IMPOSTOR_ROLE),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-sketch-full',
    label: 'SS · Sketch · canvas full (1 000 points)',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('SKETCH', sfx.ARTIST_ROLE, {
      draft: {
        strokes: sfx.maxDrawing(sfx.VIEWER_ARTIST, 1).strokes,
        canvasBudget: { points: 1000, maxPoints: 1000, strokes: 60, maxStrokes: 60 },
      },
    }),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-sketch-spectator',
    label: 'SS · Sketch · spectator',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('SKETCH', null, { draft: null }),
    viewer: 'ss-spectator',
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-gallery',
    label: 'SS · Gallery · vote & chat',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('GALLERY', sfx.ARTIST_ROLE),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-guess-impostor',
    label: 'SS · Guess · caught impostor',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('GUESS', sfx.AWARE_IMPOSTOR_ROLE),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-guess-artist',
    label: 'SS · Guess · artist watching',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('GUESS', sfx.ARTIST_ROLE),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-end-caught',
    label: 'SS · Round end · CAUGHT (with decoy)',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROUND_END', sfx.ARTIST_ROLE),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-guess-unaware',
    label: 'SS · Guess · unaware impostor (role learned now)',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('GUESS', sfx.UNAWARE_IMPOSTOR_ROLE),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-gallery-max',
    label: 'SS · Gallery · 10 artists: 9 full drawings + 1 empty',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('GALLERY', sfx.ARTIST_ROLE, {
      gallery: sfx.maxGallery,
      order: sfx.SS_PLAYERS_10.map((p) => p.guestId),
      players: sfx.SS_ROSTER_10,
      nicknames: Object.fromEntries(sfx.SS_PLAYERS_10.map((p) => [p.guestId, p.nickname])),
    }),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS_10,
  },
  {
    id: 'ss-gallery-spectator',
    label: 'SS · Gallery · spectator',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('GALLERY', null),
    viewer: 'ss-spectator',
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-end-unaware-no-guess',
    label: 'SS · Round end · unaware impostor caught, no guess',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROUND_END', sfx.UNAWARE_IMPOSTOR_ROLE, {
      roundEnd: sfx.roundEndedCaughtNoGuess,
    }),
    viewer: sfx.VIEWER_IMPOSTOR,
    players: sfx.SS_PLAYERS,
  },
  {
    id: 'ss-end-escaped',
    label: 'SS · Round end · ESCAPED',
    gameKey: 'sketchy-saurus',
    snapshot: sfx.sketchySnapshot('ROUND_END', sfx.ARTIST_ROLE, {
      roundEnd: sfx.roundEndedEscaped,
    }),
    viewer: sfx.VIEWER_ARTIST,
    players: sfx.SS_PLAYERS,
  },
]

function load(scenario: Scenario) {
  const players =
    scenario.players ?? (scenario.gameKey === 'sketchy-saurus' ? sfx.SS_PLAYERS : fx.DI_PLAYERS)
  const now = Date.now()
  const room: RoomDto = {
    id: 'room-dev',
    code: 'DEVDI1',
    inviteUrl: 'http://localhost/join/DEVDI1',
    status: 'PLAYING',
    gameKey: scenario.gameKey,
    hostGuestId: players[0].guestId,
    minPlayers: 4,
    maxPlayers: 10,
    settings: {},
    players,
    sessionId: scenario.snapshot.sessionId,
    createdAt: new Date(now).toISOString(),
  }
  useRoomStore.getState().reset()
  useRoomStore.getState().applySnapshot({
    room,
    serverNow: new Date(now).toISOString(),
    seq: { room: 1, private: 1 },
    // Fixture deadlines are far away; restart the clock so the countdowns look real.
    game: {
      ...scenario.snapshot,
      deadlineAt: new Date(now + 25_000).toISOString(),
    } as unknown as GameSnapshot,
  })
}

export function Component() {
  const [scenarioId, setScenarioId] = React.useState(SCENARIOS[0].id)
  const [log, setLog] = React.useState<string[]>([])
  const scenario = SCENARIOS.find((s) => s.id === scenarioId) ?? SCENARIOS[0]

  React.useEffect(() => {
    load(scenario)
  }, [scenario])

  // Stands in for the STOMP client: logs each command and acknowledges it like a friendly server.
  const client = React.useMemo(() => {
    const sendCommand = (command: ClientCommand) => {
      const actionId = command.actionId ?? crypto.randomUUID()
      setLog((prev) => [`${command.type} ${JSON.stringify(command.payload)}`, ...prev].slice(0, 12))
      setTimeout(() => {
        if (scenario.gameKey === 'sketchy-saurus') {
          const store = useSketchySaurusStore.getState()
          if (command.type === 'IMPOSTOR_CHAT') {
            const text = (command.payload as { text: string }).text
            store.applyChatMessage({
              guestId: scenario.viewer,
              text,
              ts: new Date().toISOString(),
            })
          }
          if (command.type === 'IMPOSTOR_VOTE') {
            const voted = new Set(store.votedGuestIds)
            if ((command.payload as ImpostorVotePayload).targetGuestId) voted.add(scenario.viewer)
            else voted.delete(scenario.viewer)
            store.applyVoteProgress({
              phaseId: scenario.snapshot.phaseId,
              votedGuestIds: [...voted],
            })
          }
          store.applyActionAck(actionId)
        } else {
          const store = useDinoImpostorStore.getState()
          if (command.type === 'IMPOSTOR_CHAT') {
            const text = (command.payload as { text: string }).text
            store.applyChatMessage({
              guestId: scenario.viewer,
              text,
              ts: new Date().toISOString(),
            })
          }
          if (command.type === 'IMPOSTOR_VOTE') {
            const voted = new Set(store.votedGuestIds)
            if ((command.payload as ImpostorVotePayload).targetGuestId) voted.add(scenario.viewer)
            else voted.delete(scenario.viewer)
            store.applyVoteProgress({
              phaseId: scenario.snapshot.phaseId,
              votedGuestIds: [...voted],
            })
          }
          store.applyActionAck(actionId)
        }
      }, 250)
      return actionId
    }
    return { sendCommand } as unknown as RealtimeClient
  }, [scenario])

  const playLiveStroke = () => {
    const drawer = useDinoImpostorStore.getState().drawerGuestId
    if (!drawer) return
    fx.strokeBatches(drawer, `dev-${Date.now()}`).forEach((batch, i) =>
      setTimeout(() => useDinoImpostorStore.getState().applyDrawingUpdated(batch), 300 * (i + 1)),
    )
  }

  const activePlayers =
    scenario.players ?? (scenario.gameKey === 'sketchy-saurus' ? sfx.SS_PLAYERS : fx.DI_PLAYERS)

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-4 p-4 lg:flex-row">
      <aside className="flex w-full shrink-0 flex-col gap-3 lg:w-64">
        <h1 className="font-fredoka text-xl font-semibold">Impostor games preview (DEV)</h1>
        <label className="flex flex-col gap-1 text-sm font-semibold">
          Scenario
          <select
            className="rounded-lg border border-border bg-card p-2 font-normal"
            value={scenarioId}
            onChange={(e) => setScenarioId(e.target.value)}
          >
            {SCENARIOS.map((s) => (
              <option key={s.id} value={s.id}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <p className="text-sm text-muted-foreground">Viewer: {scenario.viewer}</p>
        {scenario.gameKey === 'dino-impostor' && (
          <button
            type="button"
            className="rounded-lg border border-border bg-card px-3 py-2 text-sm font-semibold"
            onClick={playLiveStroke}
          >
            Play a live stroke by the drawer
          </button>
        )}
        <section aria-label="Sent commands" className="text-xs">
          <h2 className="font-semibold">Sent commands</h2>
          {/* Fixed height: a growing log would push the canvas down in the middle of a stroke on phones. */}
          <ol className="mt-1 flex h-28 flex-col gap-1 overflow-y-auto break-all font-mono">
            {log.map((line, i) => (
              <li key={`${i}-${line}`}>{line}</li>
            ))}
          </ol>
        </section>
      </aside>
      <main className="flex min-h-[80vh] flex-1 flex-col">
        <RealtimeContext.Provider value={client}>
          {scenario.gameKey === 'sketchy-saurus' ? (
            <SketchySaurusStage
              key={scenario.id}
              players={activePlayers}
              currentGuestId={scenario.viewer}
            />
          ) : (
            <DinoImpostorStage
              key={scenario.id}
              players={activePlayers}
              currentGuestId={scenario.viewer}
            />
          )}
        </RealtimeContext.Provider>
      </main>
    </div>
  )
}
