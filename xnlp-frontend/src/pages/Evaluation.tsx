import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { modelsApi, datasetsApi, evaluationsApi } from '../api/client'
import { BarChart3, CircleStop, Loader2, Play, RefreshCw, Search, X } from 'lucide-react'

const TASK_TYPES = ['TEXT_CLASSIFICATION', 'SENTIMENT_ANALYSIS', 'SUMMARIZATION',
  'NAMED_ENTITY_RECOGNITION', 'QUESTION_ANSWERING', 'TRANSLATION']
const TERMINAL = new Set(['completed', 'failed', 'cancelled'])

type EvaluationRun = {
  id: string
  modelName: string
  datasetName: string
  taskType: string
  status: string
  metrics?: any
  elapsedSeconds?: number
  totalEntries?: number
  processedEntries?: number
  progressPercent?: number
  errorMessage?: string
}

export default function Evaluation() {
  const { t } = useTranslation()
  const [models, setModels] = useState<any[]>([])
  const [datasets, setDatasets] = useState<any[]>([])
  const [evaluations, setEvaluations] = useState<EvaluationRun[]>([])
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [modelName, setModelName] = useState('')
  const [datasetId, setDatasetId] = useState('')
  const [taskType, setTaskType] = useState('')
  const [modelFilter, setModelFilter] = useState('')
  const [datasetFilter, setDatasetFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [error, setError] = useState('')
  const [selectedRun, setSelectedRun] = useState<EvaluationRun | null>(null)

  const loadRuns = useCallback(async (showLoading = false) => {
    if (showLoading) setLoading(true)
    try {
      const runs = await evaluationsApi.list({
        modelName: modelFilter || undefined,
        datasetName: datasetFilter || undefined,
        status: statusFilter || undefined,
      })
      setEvaluations(runs)
    } catch (e: any) {
      setError(e.message || t('evaluation.loadFailed'))
    } finally {
      if (showLoading) setLoading(false)
    }
  }, [datasetFilter, modelFilter, statusFilter, t])

  const loadData = useCallback(async () => {
    try {
      const [m, d] = await Promise.all([modelsApi.list(), datasetsApi.list()])
      setModels(m.filter((model: any) => !model.type || model.type === 'CHAT'))
      setDatasets(d)
    } catch (e: any) {
      setError(e.message || t('evaluation.loadFailed'))
    }
    await loadRuns(true)
  }, [loadRuns, t])

  useEffect(() => { loadData() }, [loadData])

  // Poll only while there is active work. This keeps the page responsive while
  // avoiding a permanent request loop when the history is idle.
  const hasActiveRuns = evaluations.some(run => !TERMINAL.has(run.status))
  useEffect(() => {
    if (!hasActiveRuns) return
    const timer = window.setInterval(() => { void loadRuns() }, 2000)
    return () => window.clearInterval(timer)
  }, [hasActiveRuns, loadRuns])

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
      await loadRuns()
    } catch (e: any) {
      setError(e.message)
    } finally {
      setRunning(false)
    }
  }

  const handleCancel = async (id: string) => {
    try {
      await evaluationsApi.cancel(id)
      await loadRuns()
    } catch (e: any) {
      setError(e.message || t('evaluation.cancelFailed'))
    }
  }

  const formatPct = (v: number | null | undefined) =>
    v != null ? (v * 100).toFixed(1) + '%' : '-'
  const formatNum = (v: number | null | undefined) =>
    v != null ? v.toFixed(4) : '-'
  const progress = (run: EvaluationRun) => Math.max(0, Math.min(100, run.progressPercent ?? 0))
  const activeCount = useMemo(() => evaluations.filter(run => !TERMINAL.has(run.status)).length, [evaluations])

  return (
    <div className="space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <p className="eyebrow">{t('evaluation.eyebrow')}</p>
          <h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">{t('evaluation.title')}</h1>
          <p className="mt-1 text-sm text-slate-500">{t('evaluation.subtitle')}</p>
        </div>
        <button onClick={() => void loadRuns()} className="button-secondary self-start sm:self-auto">
          <RefreshCw className="h-4 w-4" />{t('evaluation.refresh')}
        </button>
      </div>

      <section className="surface p-5 sm:p-6">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold text-slate-900">{t('evaluation.runTitle')}</h2>
            <p className="mt-1 text-xs text-slate-500">{t('evaluation.runSubtitle')}</p>
          </div>
          {activeCount > 0 && <span className="rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold text-blue-700">{t('evaluation.activeCount', { count: activeCount })}</span>}
        </div>
        {error && <div className="mt-4 rounded-xl border border-red-100 bg-red-50 px-3 py-2 text-sm text-red-600">{error}</div>}
        <div className="mt-5 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          <Field label={t('evaluation.model')}>
            <select value={modelName} onChange={e => setModelName(e.target.value)} className="input">
              <option value="">{t('evaluation.selectModel')}</option>
              {models.map((m: any) => <option key={m.name} value={m.name}>{m.name} ({m.protocol || m.provider})</option>)}
            </select>
          </Field>
          <Field label={t('evaluation.dataset')}>
            <select value={datasetId} onChange={e => setDatasetId(e.target.value)} className="input">
              <option value="">{t('evaluation.selectDataset')}</option>
              {datasets.map((d: any) => <option key={d.id} value={d.id}>{d.name} ({d.entryCount ?? d.entries?.length ?? 0})</option>)}
            </select>
          </Field>
          <Field label={t('evaluation.taskType')}>
            <select value={taskType} onChange={e => setTaskType(e.target.value)} className="input">
              <option value="">{t('evaluation.autoFromDataset')}</option>
              {TASK_TYPES.map(task => <option key={task} value={task}>{t(`tasks.${task}`, { defaultValue: task })}</option>)}
            </select>
          </Field>
        </div>
        <button onClick={handleRun} disabled={running} className="button-primary mt-5">
          {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
          {running ? t('evaluation.running') : t('evaluation.run')}
        </button>
      </section>

      <section className="surface overflow-hidden">
        <div className="flex flex-col gap-4 border-b border-slate-100 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <div>
            <h2 className="text-sm font-semibold text-slate-900">{t('evaluation.history')}</h2>
            <p className="mt-1 text-xs text-slate-500">{t('evaluation.historySubtitle')}</p>
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <FilterInput icon={<Search className="h-3.5 w-3.5" />} value={modelFilter} onChange={setModelFilter} placeholder={t('evaluation.filterModel')} />
            <FilterInput value={datasetFilter} onChange={setDatasetFilter} placeholder={t('evaluation.filterDataset')} />
            <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)} className="input h-9 text-xs">
              <option value="">{t('evaluation.allStatuses')}</option>
              {['queued', 'running', 'cancelling', 'completed', 'failed', 'cancelled'].map(status => <option key={status} value={status}>{t(`statuses.${status}`, { defaultValue: status })}</option>)}
            </select>
          </div>
        </div>

        {loading ? <div className="flex items-center justify-center p-12 text-sm text-slate-400"><Loader2 className="mr-2 h-4 w-4 animate-spin" />{t('common.loading')}</div> : evaluations.length === 0 ? (
          <div className="p-12 text-center text-sm text-slate-400">{t('evaluation.noEvaluations')}</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-[900px] w-full text-left text-sm">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-400">
                <tr><th className="px-5 py-3 font-medium">{t('evaluation.model')}</th><th className="px-5 py-3 font-medium">{t('evaluation.dataset')}</th><th className="px-5 py-3 font-medium">{t('evaluation.task')}</th><th className="px-5 py-3 font-medium">{t('evaluation.status')}</th><th className="px-5 py-3 font-medium">{t('evaluation.progress')}</th><th className="px-5 py-3 font-medium">{t('evaluation.accuracy')}</th><th className="px-5 py-3 font-medium">{t('evaluation.time')}</th><th className="px-5 py-3 font-medium">{t('evaluation.details')}</th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {evaluations.map(run => (
                  <tr key={run.id} className="transition hover:bg-slate-50/70">
                    <td className="max-w-[180px] break-words px-5 py-4 font-medium text-slate-800">{run.modelName}</td>
                    <td className="max-w-[180px] break-words px-5 py-4 text-slate-600">{run.datasetName}</td>
                    <td className="px-5 py-4 text-xs text-slate-500">{t(`tasks.${run.taskType}`, { defaultValue: run.taskType })}</td>
                    <td className="px-5 py-4"><StatusBadge status={run.status} /></td>
                    <td className="w-48 px-5 py-4"><Progress run={run} /></td>
                    <td className="px-5 py-4 text-slate-600">{formatPct(run.metrics?.accuracy)}</td>
                    <td className="whitespace-nowrap px-5 py-4 text-xs text-slate-400">{run.elapsedSeconds?.toFixed(1) ?? '0.0'}s</td>
                    <td className="px-5 py-4"><div className="flex items-center gap-2"><button onClick={() => setSelectedRun(run)} className="rounded-lg p-2 text-blue-600 transition hover:bg-blue-50" aria-label={t('evaluation.details')}><BarChart3 className="h-4 w-4" /></button>{!TERMINAL.has(run.status) && <button onClick={() => void handleCancel(run.id)} className="rounded-lg p-2 text-rose-500 transition hover:bg-rose-50" aria-label={t('evaluation.cancel')}><CircleStop className="h-4 w-4" /></button>}</div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {selectedRun && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/40 p-4" onClick={() => setSelectedRun(null)}>
          <div className="max-h-[80vh] w-full max-w-lg overflow-y-auto rounded-2xl bg-white p-5 shadow-2xl sm:p-6" onClick={e => e.stopPropagation()}>
            <div className="flex items-start justify-between gap-4"><div><p className="eyebrow">{t('evaluation.details')}</p><h3 className="mt-2 break-words text-lg font-semibold text-slate-900">{selectedRun.modelName} / {selectedRun.datasetName}</h3></div><button onClick={() => setSelectedRun(null)} className="rounded-lg p-2 text-slate-400 hover:bg-slate-100"><X className="h-4 w-4" /></button></div>
            <div className="mt-5"><StatusBadge status={selectedRun.status} /><Progress run={selectedRun} /></div>
            {selectedRun.metrics ? <div className="mt-5 grid grid-cols-1 gap-3 text-sm sm:grid-cols-2"><MetricRow label={t('metrics.accuracy')} value={formatPct(selectedRun.metrics.accuracy)} /><MetricRow label={t('metrics.precisionMacro')} value={formatNum(selectedRun.metrics.precisionMacro)} /><MetricRow label={t('metrics.recallMacro')} value={formatNum(selectedRun.metrics.recallMacro)} /><MetricRow label={t('metrics.f1Macro')} value={formatNum(selectedRun.metrics.f1Macro)} />{selectedRun.metrics.rouge1 != null && <MetricRow label={t('metrics.rouge1')} value={formatNum(selectedRun.metrics.rouge1)} />}<MetricRow label={t('evaluation.totalEntries')} value={String(selectedRun.metrics.totalEntries ?? selectedRun.totalEntries ?? 0)} /><MetricRow label={t('evaluation.correct')} value={String(selectedRun.metrics.correctEntries ?? 0)} /></div> : <p className="mt-5 text-sm text-slate-400">{t('evaluation.noMetrics')}</p>}
            {selectedRun.errorMessage && <div className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-700">{selectedRun.errorMessage}</div>}
          </div>
        </div>
      )}
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <div><label className="mb-1.5 block text-xs font-medium text-slate-500">{label}</label>{children}</div>
}

function FilterInput({ icon, value, onChange, placeholder }: { icon?: ReactNode; value: string; onChange: (value: string) => void; placeholder: string }) {
  return <label className="flex h-9 items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 text-slate-400"><span>{icon}</span><input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} className="min-w-0 flex-1 bg-transparent text-xs text-slate-700 outline-none placeholder:text-slate-400" /></label>
}

function StatusBadge({ status }: { status: string }) {
  const style = status === 'completed' ? 'bg-emerald-50 text-emerald-700' : status === 'failed' ? 'bg-rose-50 text-rose-700' : status === 'cancelled' ? 'bg-slate-100 text-slate-600' : 'bg-amber-50 text-amber-700'
  return <span className={`inline-flex rounded-full px-2.5 py-1 text-[11px] font-semibold ${style}`}>{status}</span>
}

function Progress({ run }: { run: EvaluationRun }) {
  const value = Math.max(0, Math.min(100, run.progressPercent ?? 0))
  return <div><div className="mb-1 flex justify-between text-[11px] text-slate-400"><span>{run.processedEntries ?? 0}/{run.totalEntries ?? 0}</span><span>{value.toFixed(0)}%</span></div><div className="h-1.5 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-blue-500 transition-all" style={{ width: `${value}%` }} /></div></div>
}

function MetricRow({ label, value }: { label: string; value: string }) {
  return <div className="flex justify-between border-b border-slate-100 pb-2"><span className="text-slate-500">{label}</span><span className="font-mono font-medium text-slate-900">{value}</span></div>
}
