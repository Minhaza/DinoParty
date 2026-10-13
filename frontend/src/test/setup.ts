import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

import { useConnectionStore } from '@/features/realtime/connectionStore'
import { getSharedRealtimeClient } from '@/features/realtime/realtimeContext'
import { useRoomStore } from '@/stores/roomStore'

afterEach(() => {
  cleanup()
  getSharedRealtimeClient().disconnect()
  useRoomStore.getState().reset()
  useConnectionStore.getState().reset()
})
