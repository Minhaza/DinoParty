// DEV-ONLY fixture preview for the Dino Impostor screens (P9-05 browser check). Mounted at
// `/__dev/impostor` only when `import.meta.env.DEV` is true (app/routes.tsx) and linked from nowhere;
// the production build contains neither this page nor the fixtures it imports.

import * as React from 'react'

import type { ClientCommand, GameSnapshot, PlayerDto, RoomDto } from '@/api/types'
import type { RealtimeClient } from '@/features/realtime/realtimeClient'
import { RealtimeContext } from '@/features/realtime/realtimeContext'
import type {
  DinoImpostorSnapshot,
  ImpostorOutcome,
  ImpostorVotePayload,
} from '@/features/impostor/common/types'
import { DinoImpostorStage } from '@/features/impostor/dino/DinoImpostorStage'
import { useDinoImpostorStore } from '@/features/impostor/dino/dinoImpostorStore'
import * as fx from '@/features/impostor/dino/fixtures'
import { useRoomStore } from '@/stores/roomStore'

interface Scenario {
  id: string
  label: string
  snapshot: DinoImpostorSnapshot
  viewer: string
  players?: PlayerDto[]
}

const OUTCOMES: ImpostorOutcome[] = ['ESCAPED', 'GUESSED', 'CAUGHT', 'IMPOSTOR_LEFT']

const SCENARIOS: Scenario[] = [
  {
    id: 'role-artist',
    label: 'Role reveal · artist',
    snapshot: fx.roleRevealArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'role-impostor',
    label: 'Role reveal · impostor',
    snapshot: fx.roleRevealImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'role-spectator',
    label: 'Role reveal · spectator',
    snapshot: fx.roleRevealSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  {
    id: 'draw-mine',
    label: 'Drawing · my turn (artist)',
    snapshot: fx.drawingMyTurnArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'draw-other',
    label: 'Drawing · watching (artist)',
    snapshot: fx.drawingOtherTurnArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'draw-impostor',
    label: 'Drawing · watching (impostor)',
    snapshot: fx.drawingOtherTurnImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'draw-spectator',
    label: 'Drawing · spectator',
    snapshot: fx.drawingSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  {
    id: 'draw-full',
    label: 'Drawing · full canvas, 10 players',
    snapshot: fx.drawingFullCanvas,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'draw-four',
    label: 'Drawing · 4 players, first turn',
    snapshot: fx.drawingFourPlayers,
    viewer: 'di-g2',
    players: fx.DI_PLAYERS_4,
  },
  {
    id: 'vote-artist',
    label: 'Vote · artist (voted)',
    snapshot: fx.voteArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'vote-impostor',
    label: 'Vote · impostor',
    snapshot: fx.voteImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'vote-spectator',
    label: 'Vote · spectator',
    snapshot: fx.voteSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  {
    id: 'guess-impostor',
    label: 'Guess · caught impostor',
    snapshot: fx.guessAsImpostor,
    viewer: fx.VIEWER_IMPOSTOR,
  },
  {
    id: 'guess-artist',
    label: 'Guess · artist waiting',
    snapshot: fx.guessAsArtist,
    viewer: fx.VIEWER_ARTIST,
  },
  {
    id: 'guess-spectator',
    label: 'Guess · spectator',
    snapshot: fx.guessSpectator,
    viewer: fx.VIEWER_SPECTATOR,
  },
  ...OUTCOMES.map((o) => ({
    id: `end-${o}`,
    label: `Round end · ${o}`,
    snapshot: fx.roundEndSnapshot(o),
    viewer: fx.VIEWER_ARTIST,
  })),
]

function load(scenario: Scenario) {
  const players = scenario.players ?? fx.DI_PLAYERS
  const now = Date.now()
  const room: RoomDto = {
    id: 'room-dev',
    code: 'DEVDI1',
    inviteUrl: 'http://localhost/join/DEVDI1',
    status: 'PLAYING',
    gameKey: 'dino-impostor',
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
        const store = useDinoImpostorStore.getState()
        if (command.type === 'IMPOSTOR_CHAT') {
          const text = (command.payload as { text: string }).text
          store.applyChatMessage({ guestId: scenario.viewer, text, ts: new Date().toISOString() })
        }
        if (command.type === 'IMPOSTOR_VOTE') {
          const voted = new Set(store.votedGuestIds)
          if ((command.payload as ImpostorVotePayload).targetGuestId) voted.add(scenario.viewer)
          else voted.delete(scenario.viewer)
          store.applyVoteProgress({ phaseId: scenario.snapshot.phaseId, votedGuestIds: [...voted] })
        }
        store.applyActionAck(actionId)
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

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-4 p-4 lg:flex-row">
      <aside className="flex w-full shrink-0 flex-col gap-3 lg:w-64">
        <h1 className="font-fredoka text-xl font-semibold">Dino Impostor preview (DEV)</h1>
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
        <button
          type="button"
          className="rounded-lg border border-border bg-card px-3 py-2 text-sm font-semibold"
          onClick={playLiveStroke}
        >
          Play a live stroke by the drawer
        </button>
        <section aria-label="Sent commands" className="text-xs">
          <h2 className="font-semibold">Sent commands</h2>
          <ol className="mt-1 flex flex-col gap-1 break-all font-mono">
            {log.map((line, i) => (
              <li key={`${i}-${line}`}>{line}</li>
            ))}
          </ol>
        </section>
      </aside>
      <main className="flex min-h-[80vh] flex-1 flex-col">
        <RealtimeContext.Provider value={client}>
          <DinoImpostorStage
            key={scenario.id}
            players={scenario.players ?? fx.DI_PLAYERS}
            currentGuestId={scenario.viewer}
          />
        </RealtimeContext.Provider>
      </main>
    </div>
  )
}
