import type { ThemePreference } from '@/stores/prefsStore'

export function resolveDark(theme: ThemePreference, systemPrefersDark: boolean): boolean {
  return theme === 'dark' || (theme === 'system' && systemPrefersDark)
}
