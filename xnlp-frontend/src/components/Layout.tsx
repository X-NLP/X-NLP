import { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LayoutDashboard, Database, FlaskConical, GitCompare, Activity, ServerCog, Workflow } from 'lucide-react'
import LanguageSwitcher from './LanguageSwitcher'

const navItems = [
  { to: '/', labelKey: 'nav.dashboard', icon: LayoutDashboard },
  { to: '/models', labelKey: 'nav.models', icon: ServerCog },
  { to: '/datasets', labelKey: 'nav.datasets', icon: Database },
  { to: '/evaluation', labelKey: 'nav.evaluation', icon: FlaskConical },
  { to: '/canvas', labelKey: 'nav.canvas', icon: Workflow },
  { to: '/compare', labelKey: 'nav.compare', icon: GitCompare },
]

export default function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation()

  return (
    <div className="min-h-screen bg-gray-50">
      <header className="sticky top-0 z-40 border-b bg-white/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-3 sm:px-6">
          <div className="flex shrink-0 items-center gap-2 pr-2">
            <Activity className="h-5 w-5 text-emerald-500" />
            <span className="text-lg font-semibold tracking-tight text-gray-950">X-NLP</span>
          </div>
          <nav className="flex min-w-0 flex-1 gap-1 overflow-x-auto">
          {navItems.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-gray-900 text-white'
                    : 'text-gray-500 hover:bg-gray-100 hover:text-gray-900'
                }`
              }
            >
              <item.icon className="h-4 w-4" />
              {t(item.labelKey)}
            </NavLink>
          ))}
          </nav>
          <LanguageSwitcher />
        </div>
      </header>
      <main className="min-w-0">
        <div className="mx-auto max-w-7xl px-4 py-5 sm:px-6 md:py-8">
          {children}
        </div>
      </main>
    </div>
  )
}
