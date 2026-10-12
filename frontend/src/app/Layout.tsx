import { Monitor, Moon, Sun } from 'lucide-react'
import { useEffect } from 'react'
import { NavLink, Outlet } from 'react-router'

import { Button } from '@/components/ui/button'
import { resolveDark } from '@/lib/theme'
import { cn } from '@/lib/utils'
import { usePrefsStore, type ThemePreference } from '@/stores/prefsStore'

const NEXT_THEME: Record<ThemePreference, ThemePreference> = {
  system: 'light',
  light: 'dark',
  dark: 'system',
}

const THEME_ICON = { system: Monitor, light: Sun, dark: Moon } as const

function useApplyTheme(theme: ThemePreference) {
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const apply = () =>
      document.documentElement.classList.toggle('dark', resolveDark(theme, media.matches))
    apply()
    media.addEventListener('change', apply)
    return () => media.removeEventListener('change', apply)
  }, [theme])
}

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    'rounded-md px-3 py-1.5 text-sm font-medium outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
    isActive ? 'bg-secondary text-secondary-foreground' : 'text-muted-foreground hover:bg-muted',
  )

export function Layout() {
  const theme = usePrefsStore((s) => s.theme)
  const setTheme = usePrefsStore((s) => s.setTheme)
  useApplyTheme(theme)
  const ThemeIcon = THEME_ICON[theme]

  return (
    <div className="flex min-h-screen flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:rounded-md focus:bg-card focus:px-3 focus:py-2"
      >
        Skip to content
      </a>
      <header className="border-b">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-3">
          <NavLink to="/" className="text-lg font-bold tracking-tight">
            DinoParty
          </NavLink>
          <nav aria-label="Main" className="flex items-center gap-1">
            <NavLink to="/games" className={navLinkClass}>
              Games
            </NavLink>
            <NavLink to="/join" className={navLinkClass}>
              Join
            </NavLink>
            <NavLink to="/settings" className={navLinkClass}>
              Settings
            </NavLink>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setTheme(NEXT_THEME[theme])}
              aria-label={`Theme: ${theme}. Switch to ${NEXT_THEME[theme]}.`}
            >
              <ThemeIcon className="size-4" aria-hidden />
            </Button>
          </nav>
        </div>
      </header>
      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
