import { ReactNode, useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Activity,
  Bot,
  Database,
  GitCompare,
  LayoutDashboard,
  Menu,
  ScanText,
  ServerCog,
  FlaskConical,
  Workflow,
  Truck,
  X,
} from 'lucide-react'
import LanguageSwitcher from './LanguageSwitcher'
import { healthApi } from '../api/client'

const navItems = [
  { to: '/', labelKey: 'nav.dashboard', icon: LayoutDashboard, end: true },
  { to: '/assistant', labelKey: 'nav.assistant', icon: Bot },
  { to: '/models', labelKey: 'nav.models', icon: ServerCog },
  { to: '/playground', labelKey: 'nav.playground', icon: FlaskConical },
  { to: '/nlp', labelKey: 'nav.nlp', icon: ScanText },
  { to: '/datasets', labelKey: 'nav.datasets', icon: Database },
  { to: '/evaluation', labelKey: 'nav.evaluation', icon: FlaskConical },
  { to: '/canvas', labelKey: 'nav.canvas', icon: Workflow },
  { to: '/compare', labelKey: 'nav.compare', icon: GitCompare },
  { to: '/waste', labelKey: 'nav.waste', icon: Truck },
]

export default function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [runtimeStatus, setRuntimeStatus] = useState<'loading' | 'up' | 'down'>('loading')

  useEffect(() => {
    let disposed = false
    const refresh = async () => {
      try {
        const response = await healthApi.check()
        if (!disposed) setRuntimeStatus(response?.status === 'UP' ? 'up' : 'down')
      } catch {
        if (!disposed) setRuntimeStatus('down')
      }
    }
    refresh()
    const timer = window.setInterval(refresh, 15000)
    return () => {
      disposed = true
      window.clearInterval(timer)
    }
  }, [])

  const runtimeLabel = runtimeStatus === 'up'
    ? t('runtime.online')
    : runtimeStatus === 'down'
      ? t('runtime.offline')
      : t('common.loading')
  const runtimeDot = runtimeStatus === 'up' ? 'bg-emerald-300' : runtimeStatus === 'down' ? 'bg-amber-300' : 'bg-slate-400 pulse-soft'

  return (
    <div className="min-h-screen bg-[#f6f8fb] text-slate-900">
      <aside className={`fixed inset-y-0 left-0 z-50 flex w-72 flex-col bg-[#0b1220] px-4 py-5 text-slate-300 shadow-2xl transition-transform duration-200 lg:translate-x-0 ${mobileOpen ? 'translate-x-0' : '-translate-x-full'}`}>
        <div className="flex items-center justify-between px-3">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-300 to-blue-500 text-[#0b1220] shadow-lg shadow-cyan-500/20">
              <Activity className="h-5 w-5" />
            </div>
            <div>
              <div className="text-base font-semibold tracking-wide text-white">X-NLP</div>
              <div className="text-[10px] font-medium uppercase tracking-[0.22em] text-slate-500">AI engineering OS</div>
            </div>
          </div>
          <button onClick={() => setMobileOpen(false)} className="icon-btn text-slate-500 hover:text-white lg:hidden" aria-label="Close menu">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="mt-8 px-3 text-[10px] font-semibold uppercase tracking-[0.2em] text-slate-500">Workspace</div>
        <nav className="sidebar-scroll mt-3 flex-1 space-y-1 overflow-y-auto pr-1">
          {navItems.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              onClick={() => setMobileOpen(false)}
              className={({ isActive }) => `group flex items-center gap-3 rounded-xl px-3 py-3 text-sm font-medium transition ${isActive ? 'bg-white text-slate-950 shadow-lg shadow-black/10' : 'text-slate-400 hover:bg-white/10 hover:text-white'}`}
            >
              <item.icon className="h-[18px] w-[18px] shrink-0" />
              <span>{t(item.labelKey)}</span>
            </NavLink>
          ))}
        </nav>

        <div className="mt-4 rounded-2xl border border-white/10 bg-white/[0.06] p-4">
          <div className="flex items-center justify-between text-xs font-medium text-slate-300">
            <span>Runtime</span>
            <span className={`flex items-center gap-1.5 ${runtimeStatus === 'up' ? 'text-emerald-300' : 'text-amber-300'}`}><span className={`h-1.5 w-1.5 rounded-full ${runtimeDot}`} /> {runtimeLabel}</span>
          </div>
          <p className="mt-2 text-xs leading-5 text-slate-500">{t('runtime.description')}</p>
        </div>
      </aside>

      {mobileOpen && <button className="fixed inset-0 z-40 bg-slate-950/50 lg:hidden" onClick={() => setMobileOpen(false)} aria-label="Close navigation" />}

      <div className="lg:pl-72">
        <header className="sticky top-0 z-30 border-b border-slate-200/80 bg-[#f6f8fb]/90 backdrop-blur-xl">
          <div className="mx-auto flex h-[72px] max-w-[1600px] items-center justify-between gap-4 px-4 sm:px-8">
            <div className="flex min-w-0 items-center gap-3">
              <button onClick={() => setMobileOpen(true)} className="icon-btn rounded-xl border border-slate-200 bg-white text-slate-600 lg:hidden" aria-label="Open menu">
                <Menu className="h-5 w-5" />
              </button>
              <div className="hidden min-w-0 sm:block">
                <div className="truncate text-sm font-semibold text-slate-800">{t('nav.workspaceTitle')}</div>
                <div className="truncate text-xs text-slate-400">{t('nav.workspaceSubtitle')}</div>
              </div>
            </div>
            <div className="flex items-center gap-2 sm:gap-3">
              <div className={`hidden items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-xs font-medium md:flex ${runtimeStatus === 'up' ? 'text-slate-500' : 'text-amber-600'}`}><span className={`h-2 w-2 rounded-full ${runtimeStatus === 'up' ? 'bg-emerald-400' : runtimeStatus === 'down' ? 'bg-amber-400' : 'bg-slate-300 pulse-soft'}`} /> {runtimeStatus === 'up' ? t('runtime.apiReady') : t('runtime.apiUnavailable')}</div>
              <LanguageSwitcher />
            </div>
          </div>
        </header>
        <main className="min-w-0">
          <div className="mx-auto max-w-[1600px] px-4 py-6 sm:px-8 sm:py-8">{children}</div>
        </main>
      </div>
    </div>
  )
}
