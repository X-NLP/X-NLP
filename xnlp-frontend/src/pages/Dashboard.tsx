import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { modelsApi, datasetsApi, evaluationsApi } from '../api/client'
import { Server, Database, FlaskConical, ArrowRight } from 'lucide-react'

export default function Dashboard() {
  const [models, setModels] = useState<any[]>([])
  const [datasets, setDatasets] = useState<any[]>([])
  const [evaluations, setEvaluations] = useState<any[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.allSettled([
      modelsApi.list(),
      datasetsApi.list(),
      evaluationsApi.list(),
    ]).then(([m, d, e]) => {
      if (m.status === 'fulfilled') setModels(m.value)
      if (d.status === 'fulfilled') setDatasets(d.value)
      if (e.status === 'fulfilled') setEvaluations(e.value)
      setLoading(false)
    })
  }, [])

  if (loading) {
    return <div className="text-gray-500">Loading...</div>
  }

  return (
    <div>
      <h1 className="text-xl font-semibold text-gray-900 mb-6">Overview</h1>
      <div className="grid grid-cols-3 gap-4 mb-8">
        <StatCard
          icon={Server} label="Loaded Models" count={models.length}
          color="blue" to="/models"
        />
        <StatCard
          icon={Database} label="Datasets" count={datasets.length}
          color="emerald" to="/datasets"
        />
        <StatCard
          icon={FlaskConical} label="Evaluations" count={evaluations.length}
          color="amber" to="/evaluation"
        />
      </div>

      <div className="grid grid-cols-2 gap-6">
        <div>
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Models</h2>
            <Link to="/models" className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
              View all <ArrowRight className="w-3 h-3" />
            </Link>
          </div>
          {models.length === 0 ? (
            <p className="text-sm text-gray-400">No models loaded. Start the server with a model configured.</p>
          ) : (
            <ul className="divide-y divide-gray-200 border rounded-lg bg-white">
              {models.map((m: any) => (
                <li key={m.name} className="px-4 py-3 text-sm flex items-center justify-between">
                  <div>
                    <span className="font-medium text-gray-900">{m.name}</span>
                    <span className="ml-2 text-gray-400">{m.backend || m.provider}</span>
                  </div>
                  <span className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700">
                    {m.status || 'loaded'}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Recent Evaluations</h2>
            <Link to="/compare" className="text-xs text-blue-600 hover:text-blue-800 flex items-center gap-1">
              Compare <ArrowRight className="w-3 h-3" />
            </Link>
          </div>
          {evaluations.length === 0 ? (
            <p className="text-sm text-gray-400">No evaluations yet. Run one from the Evaluation page.</p>
          ) : (
            <ul className="divide-y divide-gray-200 border rounded-lg bg-white">
              {evaluations.slice(0, 5).map((e: any) => (
                <li key={e.id} className="px-4 py-3 text-sm flex items-center justify-between">
                  <div>
                    <span className="font-medium text-gray-900">{e.modelName}</span>
                    <span className="ml-2 text-gray-400">on {e.datasetName}</span>
                  </div>
                  <span className={`text-xs px-2 py-0.5 rounded-full ${
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
