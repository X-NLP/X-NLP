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
    <div className="flex min-h-screen">
      <aside className="w-56 bg-gray-900 text-gray-300 flex flex-col shrink-0">
        <div className="flex items-center gap-2 px-4 py-5 border-b border-gray-800">
          <Activity className="w-5 h-5 text-emerald-400" />
          <span className="text-lg font-semibold text-white tracking-tight">X-NLP</span>
        </div>
        <nav className="flex-1 px-3 py-4 space-y-1">
          {navItems.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
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
        <div className="px-6 py-8 max-w-7xl mx-auto">
          {children}
        </div>
      </main>
    </div>
  )
}
