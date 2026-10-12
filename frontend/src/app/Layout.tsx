import { ChevronDown, Menu, Monitor, Moon, Sun, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router'

import { DinoAvatar } from '@/components/DinoAvatar'
import { DinoLogo } from '@/components/DinoLogo'
import { AboutModal } from '@/components/modals/AboutModal'
import { HowToPlayModal } from '@/components/modals/HowToPlayModal'
import { ProfileModal } from '@/components/modals/ProfileModal'
import { Button } from '@/components/ui/button'
import { resolveDark } from '@/lib/theme'
import { cn } from '@/lib/utils'
import { useGuestStore } from '@/stores/guestStore'
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

export function Layout() {
  const theme = usePrefsStore((s) => s.theme)
  const setTheme = usePrefsStore((s) => s.setTheme)
  useApplyTheme(theme)
  const ThemeIcon = THEME_ICON[theme]

  const nickname = useGuestStore((s) => s.nickname)
  const avatarKey = useGuestStore((s) => s.avatarKey)

  const [profileModalOpen, setProfileModalOpen] = useState(false)
  const [howToPlayModalOpen, setHowToPlayModalOpen] = useState(false)
  const [aboutModalOpen, setAboutModalOpen] = useState(false)
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)

  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    cn(
      'rounded-full px-4 py-2 text-sm font-semibold transition-all outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50',
      isActive
        ? 'bg-primary/15 text-primary'
        : 'text-foreground/75 hover:bg-muted/70 hover:text-foreground',
    )

  return (
    <div className="flex min-h-screen flex-col text-foreground antialiased selection:bg-primary/20">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-card focus:px-4 focus:py-2 focus:shadow-lg focus:outline-none focus:ring-2 focus:ring-primary"
      >
        Skip to content
      </a>

      {/* Shared Header Navigation */}
      <header className="sticky top-0 z-40 border-b border-border/70 bg-background/85 backdrop-blur-md transition-colors">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          {/* Brand Logo */}
          <NavLink
            to="/"
            className="flex items-center gap-2 rounded-2xl outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            aria-label="DinoParty home"
          >
            <DinoLogo size="md" />
          </NavLink>

          {/* Desktop Navigation Links */}
          <nav aria-label="Main" className="hidden items-center gap-1 md:flex">
            <NavLink to="/games" className={navLinkClass}>
              Games
            </NavLink>
            <button
              type="button"
              onClick={() => setHowToPlayModalOpen(true)}
              className="rounded-full px-4 py-2 text-sm font-semibold text-foreground/75 transition-all hover:bg-muted/70 hover:text-foreground cursor-pointer outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            >
              How to Play
            </button>
            <button
              type="button"
              onClick={() => setAboutModalOpen(true)}
              className="rounded-full px-4 py-2 text-sm font-semibold text-foreground/75 transition-all hover:bg-muted/70 hover:text-foreground cursor-pointer outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            >
              About
            </button>
          </nav>

          {/* Right Header Actions */}
          <div className="flex items-center gap-2">
            {/* Theme Toggle Button */}
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setTheme(NEXT_THEME[theme])}
              aria-label={`Theme: ${theme}. Switch to ${NEXT_THEME[theme]}.`}
              className="rounded-full"
            >
              <ThemeIcon className="size-4" aria-hidden />
            </Button>

            {/* Guest Profile Pill */}
            <button
              type="button"
              onClick={() => setProfileModalOpen(true)}
              aria-label={`Profile: ${nickname}. Click to edit avatar and name.`}
              className="flex items-center gap-2 rounded-full border border-border/80 bg-card px-2.5 py-1.5 shadow-2xs transition-all hover:border-primary/50 hover:bg-secondary/40 active:scale-95 cursor-pointer outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            >
              <DinoAvatar avatarKey={avatarKey} size="sm" border={false} />
              <span className="hidden text-xs font-bold text-foreground sm:inline max-w-[100px] truncate">
                {nickname}
              </span>
              <ChevronDown className="size-3.5 text-muted-foreground" />
            </button>

            {/* Mobile Menu Toggle */}
            <Button
              variant="ghost"
              size="icon"
              className="md:hidden rounded-full"
              onClick={() => setMobileMenuOpen((o) => !o)}
              aria-label={mobileMenuOpen ? 'Close menu' : 'Open menu'}
              aria-expanded={mobileMenuOpen}
            >
              {mobileMenuOpen ? <X className="size-5" /> : <Menu className="size-5" />}
            </Button>
          </div>
        </div>

        {/* Mobile Dropdown Menu */}
        {mobileMenuOpen && (
          <div className="border-t border-border/60 bg-background/95 px-4 py-3 backdrop-blur-md md:hidden animate-in slide-in-from-top-2">
            <nav aria-label="Mobile Navigation" className="flex flex-col gap-1">
              <NavLink
                to="/games"
                onClick={() => setMobileMenuOpen(false)}
                className="rounded-xl px-4 py-2.5 text-sm font-semibold hover:bg-muted"
              >
                Games
              </NavLink>
              <button
                type="button"
                onClick={() => {
                  setMobileMenuOpen(false)
                  setHowToPlayModalOpen(true)
                }}
                className="rounded-xl px-4 py-2.5 text-left text-sm font-semibold hover:bg-muted"
              >
                How to Play
              </button>
              <button
                type="button"
                onClick={() => {
                  setMobileMenuOpen(false)
                  setAboutModalOpen(true)
                }}
                className="rounded-xl px-4 py-2.5 text-left text-sm font-semibold hover:bg-muted"
              >
                About
              </button>
            </nav>
          </div>
        )}
      </header>

      {/* Main Content Area */}
      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">
        <Outlet />
      </main>

      {/* Global Modals */}
      <ProfileModal open={profileModalOpen} onOpenChange={setProfileModalOpen} />
      <HowToPlayModal open={howToPlayModalOpen} onOpenChange={setHowToPlayModalOpen} />
      <AboutModal open={aboutModalOpen} onOpenChange={setAboutModalOpen} />
    </div>
  )
}
