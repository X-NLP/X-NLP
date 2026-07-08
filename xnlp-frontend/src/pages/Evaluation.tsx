import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { modelsApi, datasetsApi, evaluationsApi } from '../api/client'
import { Play, BarChart3 } from 'lucide-react'

const TASK_TYPES = ['TEXT_CLASSIFICATION', 'SENTIMENT_ANALYSIS', 'SUMMARIZATION',
  'NAMED_ENTITY_RECOGNITION', 'QUESTION_ANSWERING', 'TRANSLATION']

export default function Evaluation() {
  const { t } = useTranslation()
  const [models, setModels] = useState<any[]>([])
  const [datasets, setDatasets] = useState<any[]>([])
  const [evaluations, setEvaluations] = useState<any[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [modelName, setModelName] = useState('')
  const [datasetId, setDatasetId] = useState('')
  const [taskType, setTaskType] = useState('')
  const [error, setError] = useState('')
  const [selectedRun, setSelectedRun] = useState<any>(null)

  const loadData = async () => {
    try {
      const [m, d, e] = await Promise.all([modelsApi.list(), datasetsApi.list(), evaluationsApi.list()])
      setModels(m.filter((model: any) => !model.type || model.type === 'CHAT'));
      setDatasets(d); setEvaluations(e)
    } catch { /* ignore */ }
    setLoading(false)
  }

  useEffect(() => { loadData() }, [])

  useEffect(() => {
    if (datasetId) {
      const ds = datasets.find(d => d.id === datasetId)
      if (ds) setTaskType(ds.taskType || '')
    }
  }, [datasetId, datasets])

  const handleRun = async () => {
    if (!modelName || !datasetId) { setError(t('evaluation.selectModelDataset')); return }
    setError(''); setRunning(true)
    try {
      await evaluationsApi.run(modelName, datasetId, taskType || undefined)
      await loadData()
    } catch (e: any) {
      setError(e.message)
    }
    setRunning(false)
  }

  const formatPct = (v: number | null | undefined) =>
    v != null ? (v * 100).toFixed(1) + '%' : '-'
  const formatNum = (v: number | null | undefined) =>
    v != null ? v.toFixed(4) : '-'

  return (
    <div>
      <h1 className="text-xl font-semibold text-gray-900 mb-6">{t('evaluation.title')}</h1>

      <div className="bg-white border rounded-lg p-4 mb-8 sm:p-6">
        <h2 className="text-sm font-semibold text-gray-700 mb-4">{t('evaluation.runTitle')}</h2>
        {error && <p className="text-sm text-red-600 mb-3">{error}</p>}
        <div className="grid grid-cols-1 gap-4 mb-4 md:grid-cols-2 xl:grid-cols-3">
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">{t('evaluation.model')}</label>
            <select value={modelName} onChange={e => setModelName(e.target.value)}
              className="w-full min-w-0 border rounded-md px-3 py-2 text-sm">
              <option value="">{t('evaluation.selectModel')}</option>
              {models.map((m: any) => <option key={m.name} value={m.name}>{m.name} ({m.protocol || m.provider})</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">{t('evaluation.dataset')}</label>
            <select value={datasetId} onChange={e => setDatasetId(e.target.value)}
              className="w-full min-w-0 border rounded-md px-3 py-2 text-sm">
              <option value="">{t('evaluation.selectDataset')}</option>
              {datasets.map((d: any) => <option key={d.id} value={d.id}>{d.name} ({t(`tasks.${d.taskType}`, { defaultValue: d.taskType })})</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-500 mb-1">{t('evaluation.taskType')}</label>
            <select value={taskType} onChange={e => setTaskType(e.target.value)}
              className="w-full min-w-0 border rounded-md px-3 py-2 text-sm">
              {TASK_TYPES.map(task => <option key={task} value={task}>{t(`tasks.${task}`, { defaultValue: task })}</option>)}
            </select>
          </div>
        </div>
        <button
          onClick={handleRun}
          disabled={running || !modelName || !datasetId}
          className="flex items-center gap-2 px-5 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <Play className="w-4 h-4" /> {running ? t('evaluation.running') : t('evaluation.run')}
        </button>
      </div>

      <h2 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-3">{t('evaluation.history')}</h2>
      {loading ? (
        <p className="text-sm text-gray-400">{t('common.loading')}</p>
      ) : evaluations.length === 0 ? (
        <p className="text-sm text-gray-400">{t('evaluation.noEvaluations')}</p>
      ) : (
        <div className="bg-white border rounded-lg overflow-x-auto">
          <table className="w-full min-w-[900px] text-sm">
            <thead className="bg-gray-50 text-left">
              <tr>
                <th className="px-4 py-3 font-medium text-gray-500">{t('evaluation.model')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('evaluation.dataset')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('evaluation.task')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('common.status')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('evaluation.accuracy')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">F1</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('evaluation.time')}</th>
                <th className="px-4 py-3 font-medium text-gray-500">{t('evaluation.details')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {evaluations.map((r: any) => (
                <tr key={r.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900 break-words">{r.modelName}</td>
                  <td className="px-4 py-3 text-gray-600 break-words">{r.datasetName}</td>
                  <td className="px-4 py-3 text-gray-500 text-xs">{t(`tasks.${r.taskType}`, { defaultValue: r.taskType })}</td>
                  <td className="px-4 py-3">
                    <span className={`text-xs px-2 py-0.5 rounded-full ${
                      r.status === 'completed' ? 'bg-green-100 text-green-700' :
                      r.status === 'failed' ? 'bg-red-100 text-red-700' : 'bg-yellow-100 text-yellow-700'
                    }`}>{t(`statuses.${r.status}`, { defaultValue: r.status })}</span>
                  </td>
                  <td className="px-4 py-3 text-gray-600">{formatPct(r.metrics?.accuracy)}</td>
                  <td className="px-4 py-3 text-gray-600">{formatNum(r.metrics?.f1Macro)}</td>
                  <td className="px-4 py-3 text-gray-400 text-xs">{r.elapsedSeconds?.toFixed(1)}s</td>
                  <td className="px-4 py-3">
                    <button onClick={() => setSelectedRun(r)}
                      className="text-blue-600 hover:text-blue-800">
                      <BarChart3 className="w-4 h-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedRun && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4" onClick={() => setSelectedRun(null)}>
          <div className="bg-white rounded-xl shadow-2xl max-w-lg w-full max-h-[80vh] overflow-y-auto p-4 sm:p-6" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="min-w-0 text-lg font-semibold break-words">{selectedRun.modelName} / {selectedRun.datasetName}</h3>
              <button onClick={() => setSelectedRun(null)} className="text-gray-400 hover:text-gray-600 text-xl">&times;</button>
            </div>
            {selectedRun.metrics ? (
              <div className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
                <MetricRow label={t('metrics.accuracy')} value={formatPct(selectedRun.metrics.accuracy)} />
                <MetricRow label={t('metrics.precisionMacro')} value={formatNum(selectedRun.metrics.precisionMacro)} />
                <MetricRow label={t('metrics.recallMacro')} value={formatNum(selectedRun.metrics.recallMacro)} />
                <MetricRow label={t('metrics.f1Macro')} value={formatNum(selectedRun.metrics.f1Macro)} />
                {selectedRun.metrics.rouge1 != null && <MetricRow label={t('metrics.rouge1')} value={formatNum(selectedRun.metrics.rouge1)} />}
                {selectedRun.metrics.rouge2 != null && <MetricRow label={t('metrics.rouge2')} value={formatNum(selectedRun.metrics.rouge2)} />}
                {selectedRun.metrics.rougeL != null && <MetricRow label={t('metrics.rougeL')} value={formatNum(selectedRun.metrics.rougeL)} />}
                {selectedRun.metrics.bleu != null && <MetricRow label={t('metrics.bleu')} value={formatNum(selectedRun.metrics.bleu)} />}
                {selectedRun.metrics.exactMatch != null && <MetricRow label={t('metrics.exactMatch')} value={formatPct(selectedRun.metrics.exactMatch)} />}
                {selectedRun.metrics.entityF1 != null && <MetricRow label={t('metrics.entityF1')} value={formatNum(selectedRun.metrics.entityF1)} />}
                <MetricRow label={t('evaluation.totalEntries')} value={String(selectedRun.metrics.totalEntries)} />
                <MetricRow label={t('evaluation.correct')} value={String(selectedRun.metrics.correctEntries)} />
              </div>
            ) : (
              <p className="text-sm text-gray-400">{t('evaluation.noMetrics')}</p>
            )}
            {selectedRun.errorMessage && (
              <div className="mt-4 p-3 bg-red-50 text-red-700 rounded-lg text-sm">{selectedRun.errorMessage}</div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function MetricRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between border-b border-gray-100 pb-2">
      <span className="text-gray-500">{label}</span>
      <span className="font-mono font-medium text-gray-900">{value}</span>
    </div>
  )
}
