import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { modelsApi, datasetsApi, evaluationsApi, nlpApi } from '../api/client'
import { Server, Database, FlaskConical, ArrowRight, Workflow } from 'lucide-react'

export default function Dashboard() {
  const { t } = useTranslation()
  const [models, setModels] = useState<any[]>([])
  const [datasets, setDatasets] = useState<any[]>([])
  const [evaluations, setEvaluations] = useState<any[]>([])
  const [tasks, setTasks] = useState<any[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.allSettled([
      modelsApi.list(),
      datasetsApi.list(),
      evaluationsApi.list(),
      nlpApi.tasks(),
    ]).then(([m, d, e, t]) => {
      if (m.status === 'fulfilled') setModels(m.value)
      if (d.status === 'fulfilled') setDatasets(d.value)
      if (e.status === 'fulfilled') setEvaluations(e.value)
      if (t.status === 'fulfilled') setTasks(t.value)
      setLoading(false)
    })
  }, [])

  if (loading) {
    return <div className="text-gray-500">{t('common.loading')}</div>
  }

  return (
    <div>
      <h1 className="text-xl font-semibold text-gray-900 mb-6">{t('dashboard.title')}</h1>
      <div className="grid grid-cols-1 gap-4 mb-8 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          icon={Server} label={t('dashboard.modelAssets')} count={models.length}
          color="blue" to="/models"
        />
        <StatCard
          icon={Workflow} label={t('dashboard.nlpCapabilities')} count={tasks.length}
          color="cyan" to="/canvas"
        />
        <StatCard
          icon={Database} label={t('dashboard.datasets')} count={datasets.length}
          color="emerald" to="/datasets"
        />
        <StatCard
          icon={FlaskConical} label={t('dashboard.evaluations')} count={evaluations.length}
          color="amber" to="/evaluation"
        />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <div className="min-w-0">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">{t('dashboard.modelAssets')}</h2>
            <Link to="/models" className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
              {t('common.viewAll')} <ArrowRight className="w-3 h-3" />
            </Link>
          </div>
          {models.length === 0 ? (
            <p className="text-sm text-gray-400">{t('dashboard.noModels')}</p>
          ) : (
            <ul className="divide-y divide-gray-200 border rounded-lg bg-white">
              {models.map((m: any) => (
                <li key={m.name} className="px-4 py-3 text-sm flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <span className="font-medium text-gray-900 break-words">{m.name}</span>
                    <span className="ml-0 block text-gray-400 break-words sm:ml-2 sm:inline">{m.backend || m.provider}</span>
                  </div>
                  <span className="w-fit shrink-0 text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700">
                    {m.status || t('common.loaded')}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="min-w-0">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">{t('dashboard.nlpCapabilities')}</h2>
            <Link to="/canvas" className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
              {t('dashboard.canvas')} <ArrowRight className="w-3 h-3" />
            </Link>
          </div>
          {tasks.length === 0 ? (
            <p className="text-sm text-gray-400">{t('dashboard.noCapabilities')}</p>
          ) : (
            <ul className="divide-y divide-gray-200 border rounded-lg bg-white">
              {tasks.slice(0, 6).map((task: any) => (
                <li key={task.task} className="px-4 py-3 text-sm">
                  <span className="font-medium text-gray-900 break-words">{task.task}</span>
                  <span className="mt-1 block text-xs text-gray-400 break-words">{task.description}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="min-w-0">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">{t('dashboard.recentEvaluations')}</h2>
            <Link to="/compare" className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
              {t('dashboard.compare')} <ArrowRight className="w-3 h-3" />
            </Link>
          </div>
          {evaluations.length === 0 ? (
            <p className="text-sm text-gray-400">{t('dashboard.noEvaluations')}</p>
          ) : (
            <ul className="divide-y divide-gray-200 border rounded-lg bg-white">
              {evaluations.slice(0, 5).map((e: any) => (
                <li key={e.id} className="px-4 py-3 text-sm flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <span className="font-medium text-gray-900 break-words">{e.modelName}</span>
                    <span className="ml-0 block text-gray-400 break-words sm:ml-2 sm:inline">{t('dashboard.onDataset', { name: e.datasetName })}</span>
                  </div>
                  <span className={`w-fit shrink-0 text-xs px-2 py-0.5 rounded-full ${
                    e.status === 'completed' ? 'bg-green-100 text-green-700' :
                    e.status === 'failed' ? 'bg-red-100 text-red-700' :
                    'bg-yellow-100 text-yellow-700'
                  }`}>
                    {e.status}
                    {e.metrics?.accuracy != null && ` · ${(e.metrics.accuracy * 100).toFixed(1)}%`}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}

function StatCard({ icon: Icon, label, count, color, to }: {
  icon: any; label: string; count: number; color: string; to: string;
}) {
  const colorMap: Record<string, string> = {
    blue: 'bg-blue-50 text-blue-600',
    cyan: 'bg-cyan-50 text-cyan-600',
    emerald: 'bg-emerald-50 text-emerald-600',
    amber: 'bg-amber-50 text-amber-600',
  }
  return (
    <Link to={to} className="bg-white border rounded-lg p-5 hover:shadow-md transition-shadow">
      <div className="flex items-center gap-3">
        <div className={`w-10 h-10 rounded-lg flex items-center justify-center ${colorMap[color]}`}>
          <Icon className="w-5 h-5" />
        </div>
        <div>
          <p className="text-2xl font-bold text-gray-900">{count}</p>
          <p className="text-xs text-gray-500">{label}</p>
        </div>
      </div>
    </Link>
  )
}
