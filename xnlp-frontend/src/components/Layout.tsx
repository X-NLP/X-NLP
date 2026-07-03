import { ReactNode } from 'react'
import { NavLink } from 'react-router-dom'
import { LayoutDashboard, Database, FlaskConical, GitCompare, Activity, ServerCog } from 'lucide-react'

const navItems = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/models', label: 'Models', icon: ServerCog },
  { to: '/datasets', label: 'Datasets', icon: Database },
  { to: '/evaluation', label: 'Evaluation', icon: FlaskConical },
  { to: '/compare', label: 'Compare', icon: GitCompare },
]

export default function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col lg:flex-row">
      <aside className="w-full bg-gray-900 text-gray-300 flex flex-col shrink-0 lg:w-56">
        <div className="flex items-center gap-2 px-4 py-4 border-b border-gray-800 lg:py-5">
          <Activity className="w-5 h-5 text-emerald-400" />
          <span className="text-lg font-semibold text-white tracking-tight">X-NLP</span>
        </div>
        <nav className="flex gap-1 overflow-x-auto px-3 py-3 lg:block lg:flex-1 lg:space-y-1 lg:overflow-visible lg:py-4">
          {navItems.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex shrink-0 items-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors lg:gap-3 ${
                  isActive
                    ? 'bg-gray-800 text-white'
                    : 'text-gray-400 hover:text-white hover:bg-gray-800'
                }`
              }
            >
              <item.icon className="w-4 h-4" />
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="flex-1 min-w-0">
        <div className="px-4 py-5 max-w-7xl mx-auto sm:px-6 md:py-8">
          {children}
        </div>
      </main>
    </div>
  )
}
