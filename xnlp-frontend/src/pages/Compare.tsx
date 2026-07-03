import { useEffect, useState } from 'react'
import { evaluationsApi } from '../api/client'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, RadarChart, PolarGrid, PolarAngleAxis, PolarRadiusAxis, Radar }
  from 'recharts'
import { GitCompare, TrendingUp, TrendingDown } from 'lucide-react'

const METRIC_LABELS: Record<string, string> = {
  accuracy: 'Accuracy', f1Macro: 'F1 (macro)', precisionMacro: 'Precision',
  recallMacro: 'Recall', rouge1: 'ROUGE-1', rouge2: 'ROUGE-2', rougeL: 'ROUGE-L',
  bleu: 'BLEU', exactMatch: 'Exact Match', f1Score: 'F1 Score', entityF1: 'Entity F1',
}

const METRIC_COLORS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899']

export default function Compare() {
  const [evaluations, setEvaluations] = useState<any[]>([])
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [compareResult, setCompareResult] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [comparing, setComparing] = useState(false)

  useEffect(() => {
    evaluationsApi.list().then(list => { setEvaluations(list); setLoading(false) }).catch(() => setLoading(false))
  }, [])

  const toggleSelect = (id: string) => {
    setSelectedIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id])
  }

  const handleCompare = async () => {
    if (selectedIds.length < 2) return
    setComparing(true)
    try {
      const result = await evaluationsApi.compare(selectedIds)
      setCompareResult(result)
    } catch { /* ignore */ }
    setComparing(false)
  }

  const pct = (v: number | null | undefined) =>
    v != null ? (v * 100).toFixed(1) + '%' : '-'
  const num = (v: number | null | undefined) =>
    v != null ? v.toFixed(4) : '-'

  const isAccuracy = (k: string) => ['accuracy', 'exactMatch'].includes(k)

  const barData = () => {
    if (!compareResult) return []
    return Object.entries(compareResult.metricValues).map(([key, values]) => {
      const obj: any = { metric: METRIC_LABELS[key] || key }
      ;(values as number[]).forEach((v, i) => {
        const run = compareResult.runs[i]
        obj[run?.modelName || `Run ${i + 1}`] = isAccuracy(key) ? (v || 0) * 100 : v
      })
      return obj
    })
  }

  const radarData = () => {
    if (!compareResult || compareResult.runs.length < 2) return []
    return compareResult.runs.map((run: any) => {
      const m = run.metrics || {}
      return {
        model: run.modelName,
        accuracy: (m.accuracy || 0) * 100,
        f1Macro: (m.f1Macro || 0) * 100,
        rouge1: (m.rouge1 || 0) * 100,
        bleu: (m.bleu || 0) * 100,
      }
    })
  }

  const runColors = compareResult?.runs.map((_: any, i: number) => METRIC_COLORS[i % METRIC_COLORS.length])

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-gray-900">Compare Evaluations</h1>
      </div>

      <div className="bg-white border rounded-lg p-4 mb-8 sm:p-6">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">Select runs to compare</h2>
        {loading ? (
          <p className="text-sm text-gray-400">Loading...</p>
        ) : evaluations.length === 0 ? (
          <p className="text-sm text-gray-400">No evaluation runs yet. Create one from the Evaluation page.</p>
        ) : (
          <>
            <div className="flex flex-wrap gap-2 mb-4">
              {evaluations.filter(r => r.status === 'completed').map((r: any) => (
                <button key={r.id}
                  onClick={() => toggleSelect(r.id)}
                  className={`px-3 py-1.5 text-xs rounded-full border transition-colors ${
                    selectedIds.includes(r.id)
                      ? 'bg-blue-600 text-white border-blue-600'
                      : 'bg-white text-gray-600 border-gray-300 hover:border-blue-400'
                  }`}
                >
                  {r.modelName} / {r.datasetName}
                </button>
              ))}
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <button
                onClick={handleCompare}
                disabled={selectedIds.length < 2 || comparing}
                className="flex items-center gap-2 px-5 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
              >
                <GitCompare className="w-4 h-4" /> {comparing ? 'Comparing...' : `Compare (${selectedIds.length} runs)`}
              </button>
              <button onClick={() => setSelectedIds([])} className="text-sm text-gray-500 hover:text-gray-700">Clear selection</button>
            </div>
          </>
        )}
      </div>

      {compareResult && (
        <div className="space-y-6">
          <div className="bg-white border rounded-lg p-4 sm:p-6">
            <h2 className="text-sm font-semibold text-gray-700 mb-4">Metric Comparison</h2>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] text-sm">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-4 py-2 text-left font-medium text-gray-500">Metric</th>
                    {compareResult.runs.map((run: any, i: number) => (
                      <th key={i} className="px-4 py-2 text-left font-medium" style={{ color: runColors?.[i] }}>
                        <span className="break-words">{run.modelName}</span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {Object.entries(compareResult.metricValues).map(([key, values]) => (
                    <tr key={key} className="hover:bg-gray-50">
                      <td className="px-4 py-2.5 text-gray-600 font-medium">{METRIC_LABELS[key] || key}</td>
                      {(values as number[]).map((v, i) => (
                        <td key={i} className="px-4 py-2.5">
                          <span className="font-mono" style={{ color: runColors?.[i] }}>
                            {isAccuracy(key) ? pct(v) : num(v)}
                          </span>
                          {compareResult.deltas[key]?.[i] != null && compareResult.deltas[key][i] !== 0 && (
                            <span className={`ml-2 text-xs inline-flex items-center gap-0.5 ${
                              compareResult.deltas[key][i] > 0 ? 'text-green-600' : 'text-red-600'
                            }`}>
                              {compareResult.deltas[key][i] > 0
                                ? <TrendingUp className="w-3 h-3" />
                                : <TrendingDown className="w-3 h-3" />
                              }
                              {(isAccuracy(key)
                                ? (compareResult.deltas[key][i] * 100).toFixed(1)
                                : compareResult.deltas[key][i].toFixed(4)
                              )}
                            </span>
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {compareResult.summary && (
              <p className="mt-4 text-sm text-gray-500 border-t pt-4">{compareResult.summary}</p>
            )}
          </div>

          <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
            <div className="min-w-0 bg-white border rounded-lg p-4 sm:p-6">
              <h2 className="text-sm font-semibold text-gray-700 mb-4">Bar Chart</h2>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={barData()} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                  <XAxis dataKey="metric" tick={{ fontSize: 12 }} />
                  <YAxis tick={{ fontSize: 12 }} />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  {compareResult.runs.map((run: any, i: number) => (
                    <Bar key={i} dataKey={run.modelName || `Run ${i + 1}`} fill={runColors?.[i]} radius={[4, 4, 0, 0]} />
                  ))}
                </BarChart>
              </ResponsiveContainer>
            </div>

            <div className="min-w-0 bg-white border rounded-lg p-4 sm:p-6">
              <h2 className="text-sm font-semibold text-gray-700 mb-4">Radar Chart</h2>
              <ResponsiveContainer width="100%" height={300}>
                <RadarChart data={radarData()}>
                  <PolarGrid />
                  <PolarAngleAxis dataKey="model" tick={{ fontSize: 12 }} />
                  <PolarRadiusAxis tick={{ fontSize: 10 }} />
                  {compareResult.runs.map((run: any, i: number) => (
                    <Radar key={i} name={run.modelName || `Run ${i + 1}`}
                      dataKey="accuracy" stroke={runColors?.[i]} fill={runColors?.[i]} fillOpacity={0.2} />
                  ))}
                </RadarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
