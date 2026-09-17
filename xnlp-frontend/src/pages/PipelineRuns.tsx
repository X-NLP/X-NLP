import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import {
  AlertCircle, ArrowDown, Ban, CheckCircle2, CircleDashed, Clock3, Download,
  GitBranch, Loader2, RefreshCw, RotateCcw, Square, XCircle,
} from 'lucide-react'
import {
  pipelinesApi,
  type PipelineNodeRun,
  type PipelineNodeTrace,
  type PipelineRun,
  type PipelineRunEvent,
  type PipelineRunStatus,
} from '../api/client'

const TERMINAL = new Set(['completed', 'failed', 'cancelled'])
const FILTERS: PipelineRunStatus[] = ['queued', 'running', 'cancelling', 'completed', 'failed', 'cancelled']
const styles: Record<string, string> = {
  queued: 'border-slate-200 bg-slate-50 text-slate-600', pending: 'border-slate-200 bg-slate-50 text-slate-600',
  running: 'border-blue-200 bg-blue-50 text-blue-700', cancelling: 'border-amber-200 bg-amber-50 text-amber-700',
  completed: 'border-emerald-200 bg-emerald-50 text-emerald-700', succeeded: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  failed: 'border-rose-200 bg-rose-50 text-rose-700', timed_out: 'border-orange-200 bg-orange-50 text-orange-700',
  cancelled: 'border-slate-300 bg-slate-100 text-slate-600',
}
const statusOf = (value?: string | null) => (value || 'pending').toLowerCase()
const date = (value?: string | null) => value ? new Date(value).toLocaleString() : '—'
const duration = (value?: number | null) => value == null ? '—' : value < 1000 ? `${value} ms` : value < 60000 ? `${(value / 1000).toFixed(2)} s` : `${Math.floor(value / 60000)}m ${Math.round(value % 60000 / 1000)}s`

function StatusIcon({ status, className = 'h-4 w-4' }: { status: string; className?: string }) {
  switch (statusOf(status)) {
    case 'completed': case 'succeeded': return <CheckCircle2 className={className} />
    case 'failed': return <XCircle className={className} />
    case 'timed_out': return <Clock3 className={className} />
    case 'cancelled': return <Ban className={className} />
    case 'running': return <Loader2 className={`${className} animate-spin`} />
    case 'cancelling': return <RotateCcw className={`${className} animate-spin`} />
    default: return <CircleDashed className={className} />
  }
}

function Panel({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-slate-200 bg-white shadow-sm ${className}`}>{children}</section>
}

export default function PipelineRuns() {
  const { t } = useTranslation()
  const [runs, setRuns] = useState<PipelineRun[]>([])
  const [selected, setSelected] = useState<PipelineRun | null>(null)
  const [events, setEvents] = useState<PipelineRunEvent[]>([])
  const [traceNodes, setTraceNodes] = useState<PipelineNodeTrace[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [error, setError] = useState('')
  const [detailError, setDetailError] = useState('')
  const [connected, setConnected] = useState(true)
  const selectedRef = useRef<PipelineRun | null>(null)
  useEffect(() => { selectedRef.current = selected }, [selected])

  const label = (status: string) => t(`pipelineRuns.status.${statusOf(status)}`, { defaultValue: status.replace(/_/g, ' ') })
  const loadRuns = useCallback(async (initial = false) => {
    initial ? setLoading(true) : setRefreshing(true)
    setError('')
    try {
      const next = await pipelinesApi.listRuns({ status: filter || undefined })
      setRuns(next)
      const currentId = selectedRef.current?.id
      if (currentId) setSelected(next.find(run => run.id === currentId) || selectedRef.current)
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t('pipelineRuns.loadFailed'))
    } finally {
      setLoading(false); setRefreshing(false)
    }
  }, [filter, t])

  const loadRun = useCallback(async (runId: string, quiet = false) => {
    if (!quiet) setDetailError('')
    try {
      const run = await pipelinesApi.getRun(runId)
      setSelected(run)
      setRuns(current => current.map(item => item.id === run.id ? run : item))
      return run
    } catch (caught) {
      if (!quiet) setDetailError(caught instanceof Error ? caught.message : t('pipelineRuns.loadFailed'))
      return null
    }
  }, [t])

  useEffect(() => { void loadRuns(true) }, [loadRuns])
  useEffect(() => {
    if (!selected) { setEvents([]); setTraceNodes([]); return }
    const runId = selected.id
    let disposed = false
    setEvents([]); setTraceNodes([]); setConnected(true)
    if (TERMINAL.has(statusOf(selected.status))) {
      void pipelinesApi.trace(runId).then(trace => {
        if (!disposed) { setTraceNodes(trace.nodes); setEvents(trace.events) }
      }).catch(() => undefined)
      return () => { disposed = true }
    }
    const stop = pipelinesApi.subscribeToRunEvents(runId, event => {
      if (disposed) return
      setConnected(true)
      setEvents(current => current.some(item => item.id === event.id) ? current : [...current, event].sort((a, b) => a.id - b.id))
      void loadRun(runId, true)
    }, () => { if (!disposed) setConnected(false) })
    return () => { disposed = true; stop() }
  }, [selected?.id, selected?.status, loadRun])

  const cancel = async () => {
    if (!selected || TERMINAL.has(statusOf(selected.status))) return
    setCancelling(true); setDetailError('')
    try {
      const run = await pipelinesApi.cancelRun(selected.id)
      setSelected(run); setRuns(current => current.map(item => item.id === run.id ? run : item))
    } catch (caught) {
      setDetailError(caught instanceof Error ? caught.message : t('pipelineRuns.cancelFailed'))
    } finally { setCancelling(false) }
  }

  const download = async () => {
    if (!selected) return
    setDownloading(true); setDetailError('')
    try {
      const trace = await pipelinesApi.trace(selected.id)
      const url = URL.createObjectURL(new Blob([JSON.stringify(trace, null, 2)], { type: 'application/json' }))
      const anchor = document.createElement('a')
      anchor.href = url; anchor.download = `pipeline-trace-${selected.id}.json`
      document.body.appendChild(anchor); anchor.click(); anchor.remove(); URL.revokeObjectURL(url)
    } catch (caught) {
      setDetailError(caught instanceof Error ? caught.message : t('pipelineRuns.downloadFailed'))
    } finally { setDownloading(false) }
  }

  const nodes = useMemo<PipelineNodeRun[]>(() => {
    if (!selected) return []
    if (!traceNodes.length) return selected.nodes
    return traceNodes.map(node => {
      const latest = node.attempts[node.attempts.length - 1]
      return { nodeId: node.nodeId, capability: node.capability, status: node.status,
        attempt: latest?.attempt || 0, maxAttempts: node.attempts.length, input: latest?.input || {}, output: node.output,
        errorCode: node.errorCode, errorMessage: node.errorMessage, startedAt: latest?.startedAt,
        completedAt: latest?.completedAt, durationMs: latest?.durationMs }
    })
  }, [selected, traceNodes])
  const attempts = useMemo(() => new Map(traceNodes.map(node => [node.nodeId, node.attempts])), [traceNodes])
  const runStatus = statusOf(selected?.status)
  const canCancel = selected && !TERMINAL.has(runStatus) && !selected.cancelRequested

  return <div className="space-y-6">
    <header className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div><p className="eyebrow">{t('pipelineRuns.eyebrow')}</p><h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900">{t('pipelineRuns.title')}</h1><p className="mt-2 max-w-3xl text-sm leading-6 text-slate-500">{t('pipelineRuns.subtitle')}</p></div>
      <button type="button" onClick={() => void loadRuns()} disabled={refreshing} className="inline-flex items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 shadow-sm hover:bg-slate-50 disabled:opacity-60" aria-label={t('pipelineRuns.refresh')}><RefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} />{t('pipelineRuns.refresh')}</button>
    </header>

    <div className="grid min-w-0 gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
      <Panel className="min-w-0 overflow-hidden xl:sticky xl:top-24 xl:max-h-[calc(100vh-8rem)]">
        <div className="border-b border-slate-100 p-4"><div className="flex items-center justify-between"><h2 className="font-semibold text-slate-900">{t('pipelineRuns.listTitle')}</h2><span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-600">{runs.length}</span></div>
          <label className="mt-4 block text-xs font-medium text-slate-500"><span>{t('pipelineRuns.filterStatus')}</span><select value={filter} onChange={event => setFilter(event.target.value)} className="mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-700 outline-none focus:border-blue-400 focus:ring-2 focus:ring-blue-100" aria-label={t('pipelineRuns.filterStatus')}><option value="">{t('pipelineRuns.allStatuses')}</option>{FILTERS.map(status => <option key={status} value={status}>{label(status)}</option>)}</select></label>
        </div>
        <div className="max-h-[620px] overflow-y-auto p-2 xl:max-h-[calc(100vh-17rem)]" aria-live="polite" aria-busy={loading}>
          {loading ? <div className="flex min-h-48 flex-col items-center justify-center gap-3 text-sm text-slate-500"><Loader2 className="h-6 w-6 animate-spin text-blue-500" />{t('pipelineRuns.loading')}</div>
            : error ? <div className="m-2 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700" role="alert"><div className="flex items-start gap-2"><AlertCircle className="mt-0.5 h-4 w-4 shrink-0" /><span>{error}</span></div></div>
            : !runs.length ? <div className="flex min-h-48 flex-col items-center justify-center p-6 text-center"><GitBranch className="h-8 w-8 text-slate-300" /><h3 className="mt-3 text-sm font-semibold text-slate-700">{t('pipelineRuns.emptyTitle')}</h3><p className="mt-1 text-xs leading-5 text-slate-500">{t('pipelineRuns.emptyDescription')}</p></div>
            : runs.map(run => { const status = statusOf(run.status); const active = selected?.id === run.id; return <button type="button" key={run.id} onClick={() => void loadRun(run.id)} aria-pressed={active} className={`mb-1 w-full rounded-xl border p-3 text-left transition focus:outline-none focus:ring-2 focus:ring-blue-200 ${active ? 'border-blue-300 bg-blue-50/70 shadow-sm' : 'border-transparent hover:border-slate-200 hover:bg-slate-50'}`}><div className="flex items-start justify-between gap-3"><span className="min-w-0 truncate font-mono text-xs font-semibold text-slate-700">{run.id}</span><span className={`inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-1 text-[10px] font-semibold uppercase tracking-wide ${styles[status] || styles.pending}`}><StatusIcon status={status} className="h-3 w-3" />{label(status)}</span></div><div className="mt-2 truncate text-xs text-slate-500">{t('pipelineRuns.pipeline')}: {run.pipelineId}</div><div className="mt-1 text-[11px] text-slate-400">{date(run.createdAt)}</div></button> })}
        </div>
      </Panel>

      {!selected ? <Panel className="flex min-h-[520px] flex-col items-center justify-center p-8 text-center"><GitBranch className="h-12 w-12 text-slate-200" /><h2 className="mt-4 text-lg font-semibold text-slate-800">{t('pipelineRuns.selectRunTitle')}</h2><p className="mt-2 max-w-md text-sm leading-6 text-slate-500">{t('pipelineRuns.selectRunDescription')}</p></Panel>
        : <div className="min-w-0 space-y-6">
          <Panel className="overflow-hidden"><div className="flex flex-col gap-4 border-b border-slate-100 p-5 md:flex-row md:items-start md:justify-between"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h2 className="break-all font-mono text-base font-semibold text-slate-900">{selected.id}</h2><span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold ${styles[runStatus] || styles.pending}`}><StatusIcon status={runStatus} />{label(runStatus)}</span>{!TERMINAL.has(runStatus) && <span className={`inline-flex items-center gap-1.5 text-xs font-medium ${connected ? 'text-emerald-600' : 'text-amber-600'}`} aria-live="polite"><span className={`h-2 w-2 rounded-full ${connected ? 'bg-emerald-400' : 'animate-pulse bg-amber-400'}`} />{t(connected ? 'pipelineRuns.live' : 'pipelineRuns.reconnecting')}</span>}</div><p className="mt-2 text-sm text-slate-500">{t('pipelineRuns.pipeline')} <span className="font-mono text-slate-700">{selected.pipelineId}</span> · {t('pipelineRuns.version')} {selected.pipelineVersion}</p></div>
            <div className="flex flex-wrap gap-2"><button type="button" onClick={() => void download()} disabled={downloading || !TERMINAL.has(runStatus)} title={!TERMINAL.has(runStatus) ? t('pipelineRuns.traceNotReady') : undefined} className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50" aria-label={t('pipelineRuns.downloadTrace')}>{downloading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}{t('pipelineRuns.downloadTrace')}</button><button type="button" onClick={() => void cancel()} disabled={!canCancel || cancelling} className="inline-flex items-center gap-2 rounded-xl bg-rose-600 px-3 py-2 text-sm font-medium text-white hover:bg-rose-700 disabled:cursor-not-allowed disabled:bg-slate-300" aria-label={t(selected.cancelRequested ? 'pipelineRuns.cancelling' : 'pipelineRuns.cancel')}>{cancelling ? <Loader2 className="h-4 w-4 animate-spin" /> : <Square className="h-4 w-4" />}{t(selected.cancelRequested ? 'pipelineRuns.cancelling' : 'pipelineRuns.cancel')}</button></div></div>
            {detailError && <div className="border-b border-rose-100 bg-rose-50 px-5 py-3 text-sm text-rose-700" role="alert">{detailError}</div>}
            <dl className="grid gap-px bg-slate-100 sm:grid-cols-2 lg:grid-cols-4">{[[t('pipelineRuns.createdAt'), date(selected.createdAt)], [t('pipelineRuns.startedAt'), date(selected.startedAt)], [t('pipelineRuns.completedAt'), date(selected.completedAt)], [t('pipelineRuns.duration'), selected.startedAt && selected.completedAt ? duration(new Date(selected.completedAt).getTime() - new Date(selected.startedAt).getTime()) : '—']].map(([term, value]) => <div key={term} className="bg-white p-4"><dt className="text-xs font-medium text-slate-400">{term}</dt><dd className="mt-1 text-sm font-medium text-slate-700">{value}</dd></div>)}</dl>
            {(selected.errorCode || selected.errorMessage) && <div className="m-5 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700"><div className="flex items-center gap-2 font-semibold"><XCircle className="h-4 w-4" />{t('pipelineRuns.error')}{selected.errorCode ? ` · ${selected.errorCode}` : ''}</div>{selected.errorMessage && <p className="mt-2 break-words text-rose-600">{selected.errorMessage}</p>}</div>}
          </Panel>

          <Panel className="overflow-hidden"><div className="flex items-center gap-2 border-b border-slate-100 px-5 py-4"><GitBranch className="h-5 w-5 text-blue-500" /><h2 className="font-semibold text-slate-900">{t('pipelineRuns.dagTitle')}</h2></div>
            {!nodes.length ? <div className="p-8 text-center text-sm text-slate-500">{t('pipelineRuns.noNodes')}</div> : <div className="overflow-x-auto p-5" aria-label={t('pipelineRuns.dagTitle')}><div className="flex min-w-max flex-col gap-0 md:flex-row md:items-stretch md:gap-3">{nodes.map((node, index) => { const status = statusOf(node.status); const nodeAttempts = attempts.get(node.nodeId) || []; return <div key={node.nodeId} className="flex flex-col md:flex-row md:items-center">{index > 0 && <div className="flex h-8 items-center justify-center text-slate-300 md:h-auto md:w-8" aria-hidden="true"><ArrowDown className="h-5 w-5 md:-rotate-90" /></div>}<article className={`w-full min-w-0 rounded-2xl border p-4 shadow-sm md:w-64 ${styles[status] || styles.pending}`}><div className="flex items-start justify-between gap-3"><div className="min-w-0"><h3 className="truncate text-sm font-semibold">{node.nodeId}</h3><p className="mt-1 truncate text-xs opacity-75">{node.capability}</p></div><StatusIcon status={status} className="h-5 w-5 shrink-0" /></div><div className="mt-3 flex flex-wrap gap-x-3 gap-y-1 text-xs opacity-80"><span>{t('pipelineRuns.attempt')} {node.attempt}/{node.maxAttempts}</span><span>{duration(node.durationMs)}</span></div>{(node.errorCode || node.errorMessage) && <div className="mt-3 rounded-lg bg-white/70 p-2 text-xs text-rose-700">{node.errorCode && <div className="font-semibold">{node.errorCode}</div>}{node.errorMessage && <div className="mt-1 break-words">{node.errorMessage}</div>}</div>}{nodeAttempts.length > 0 && <details className="mt-3 text-xs"><summary className="cursor-pointer font-semibold">{t('pipelineRuns.attempts')} ({nodeAttempts.length})</summary><ol className="mt-2 space-y-2">{nodeAttempts.map(attempt => <li key={attempt.attempt} className="rounded-lg bg-white/70 p-2"><div className="flex justify-between gap-2"><span>#{attempt.attempt} · {label(attempt.status)}</span><span>{duration(attempt.durationMs)}</span></div><div className="mt-1 opacity-70">{date(attempt.startedAt)} → {date(attempt.completedAt)}</div>{(attempt.errorCode || attempt.errorMessage) && <div className="mt-1 break-words text-rose-700">{attempt.errorCode}{attempt.errorCode && attempt.errorMessage ? ': ' : ''}{attempt.errorMessage}</div>}</li>)}</ol></details>}</article></div> })}</div></div>}
          </Panel>

          <Panel className="overflow-hidden"><div className="flex items-center justify-between gap-3 border-b border-slate-100 px-5 py-4"><div className="flex items-center gap-2"><Clock3 className="h-5 w-5 text-violet-500" /><h2 className="font-semibold text-slate-900">{t('pipelineRuns.eventsTitle')}</h2></div><span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-600">{events.length}</span></div>
            {!events.length ? <div className="p-8 text-center text-sm text-slate-500">{t('pipelineRuns.noEvents')}</div> : <ol className="divide-y divide-slate-100" aria-live="polite">{[...events].reverse().map(event => <li key={event.id} className="grid gap-2 px-5 py-4 sm:grid-cols-[150px_minmax(0,1fr)]"><time className="text-xs text-slate-400" dateTime={event.occurredAt}>{date(event.occurredAt)}</time><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="font-mono text-sm font-semibold text-slate-700">{event.type}</span>{event.nodeId && <span className="rounded bg-slate-100 px-2 py-0.5 font-mono text-xs text-slate-600">{event.nodeId}</span>}{event.status && <span className={`rounded-full border px-2 py-0.5 text-[10px] font-semibold ${styles[statusOf(event.status)] || styles.pending}`}>{label(event.status)}</span>}{event.attempt != null && <span className="text-xs text-slate-400">#{event.attempt}</span>}</div>{Object.keys(event.detail || {}).length > 0 && <pre className="mt-2 max-h-32 overflow-auto rounded-lg bg-slate-950 p-3 text-[11px] leading-5 text-slate-300">{JSON.stringify(event.detail, null, 2)}</pre>}</div></li>)}</ol>}
          </Panel>
        </div>}
    </div>
  </div>
}
