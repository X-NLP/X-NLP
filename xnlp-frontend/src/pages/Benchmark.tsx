import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Gauge,
  History,
  Loader2,
  Play,
  RotateCcw,
  ServerCog,
  TrendingUp,
  XCircle,
} from 'lucide-react'
import { modelsApi } from '../api/client'

type ModelInfo = { name: string; type?: string; provider?: string }
type BenchmarkResult = {
  model?: string
  totalRequests: number
  successfulRequests: number
  failedRequests: number
  successRate: number
  requestsPerSecond: number
  latencyAvgMs: number
  latencyP50Ms: number
  latencyP95Ms: number
  latencyP99Ms: number
  failureCode?: string | null
  failureMessage?: string | null
  runId?: string
}
type HistoryEntry = BenchmarkResult & {
  id: string
  createdAt: string
  requests: number
  concurrency: number
  text: string
}

const HISTORY_KEY = 'xnlp.benchmark.history.v1'
const LIMITS = { requests: { min: 1, max: 10000 }, concurrency: { min: 1, max: 256 } }

function errorMessage(error: unknown, fallback: string) {
  const raw = error instanceof Error ? error.message : String(error)
  const body = raw.includes(': ') ? raw.slice(raw.indexOf(': ') + 2) : raw
  try {
    const parsed = JSON.parse(body)
    return [parsed.message, parsed.error && `error=${parsed.error}`, parsed.requestId && `requestId=${parsed.requestId}`, parsed.traceId && `traceId=${parsed.traceId}`].filter(Boolean).join(' · ') || fallback
  } catch {
    return raw || fallback
  }
}

function readHistory(): HistoryEntry[] {
  try {
    const value = JSON.parse(window.sessionStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(value) ? value : []
  } catch {
    return []
  }
}

export default function Benchmark() {
  const { t, i18n } = useTranslation()
  const [models, setModels] = useState<ModelInfo[]>([])
  const [selected, setSelected] = useState('')
  const [requests, setRequests] = useState(100)
  const [concurrency, setConcurrency] = useState(4)
  const [text, setText] = useState('请用一句话介绍 X-NLP 的工程化能力。')
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<BenchmarkResult | null>(null)
  const [history, setHistory] = useState<HistoryEntry[]>([])

  const chatModels = useMemo(() => models.filter(model => !model.type || model.type === 'CHAT'), [models])
  const previous = history.find(item => item.model === selected && item.id !== result?.runId)
  const canRun = Boolean(selected && text.trim() && !running)

  useEffect(() => {
    setHistory(readHistory())
    let disposed = false
    void modelsApi.list().then(value => {
      if (disposed) return
      const next = Array.isArray(value) ? value : []
      setModels(next)
      setSelected(current => current && next.some((model: ModelInfo) => model.name === current)
        ? current
        : next.find((model: ModelInfo) => !model.type || model.type === 'CHAT')?.name || '')
    }).catch(err => {
      if (!disposed) setError(errorMessage(err, t('benchmark.noChatModel')))
    }).finally(() => {
      if (!disposed) setLoading(false)
    })
    return () => { disposed = true }
  }, [t])

  async function run(event?: FormEvent) {
    event?.preventDefault()
    if (!canRun) return
    setRunning(true)
    setError('')
    try {
      const next = await modelsApi.benchmark(selected, { requests, concurrency, text: text.trim() }) as BenchmarkResult
      const runId = `${Date.now()}`
      setResult({ ...next, runId })
      const entry: HistoryEntry = { ...next, id: runId, createdAt: new Date().toISOString(), requests, concurrency, text: text.trim() }
      const nextHistory = [entry, ...history].slice(0, 10)
      setHistory(nextHistory)
      window.sessionStorage.setItem(HISTORY_KEY, JSON.stringify(nextHistory))
    } catch (err) {
      setError(errorMessage(err, t('benchmark.requestFailed')))
    } finally {
      setRunning(false)
    }
  }

  function validateNumber(value: string, key: 'requests' | 'concurrency', setter: (value: number) => void) {
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) return
    setter(Math.max(LIMITS[key].min, Math.min(LIMITS[key].max, Math.trunc(parsed))))
  }

  return <div className="space-y-6">
    <section className="surface grid-pattern relative overflow-hidden px-6 py-7 sm:px-8">
      <div className="absolute -right-24 -top-24 h-72 w-72 rounded-full bg-cyan-400/15 blur-3xl" />
      <div className="relative flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
        <div className="max-w-3xl"><div className="eyebrow flex items-center gap-2"><Gauge className="h-3.5 w-3.5" /> {t('benchmark.eyebrow')}</div><h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">{t('benchmark.title')}</h1><p className="mt-3 max-w-2xl text-sm leading-6 text-slate-500">{t('benchmark.subtitle')}</p></div>
        <div className="flex items-center gap-2 rounded-full border border-cyan-100 bg-cyan-50 px-3 py-2 text-xs font-semibold text-cyan-700"><Activity className="h-4 w-4" /> {selected || '—'}</div>
      </div>
    </section>

    {error && <div className="flex items-start gap-3 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /><span>{error}</span></div>}

    <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(360px,0.85fr)]">
      <section className="surface overflow-hidden"><div className="flex items-center gap-3 border-b border-slate-100 px-5 py-4 sm:px-6"><div className="flex h-9 w-9 items-center justify-center rounded-xl bg-cyan-50 text-cyan-600"><ServerCog className="h-4 w-4" /></div><div><h2 className="text-sm font-semibold text-slate-900">{t('benchmark.setup')}</h2><p className="text-xs text-slate-400">{t('benchmark.setupSubtitle')}</p></div></div>
        <form onSubmit={run} className="space-y-5 p-5 sm:p-6">
          <label className="block"><span className="field-label">{t('benchmark.model')}</span><select className="input" value={selected} onChange={event => { setSelected(event.target.value); setResult(null) }} disabled={loading || chatModels.length === 0}><option value="">{loading ? t('common.loading') : chatModels.length ? t('benchmark.selectModel') : t('benchmark.noModel')}</option>{chatModels.map(model => <option key={model.name} value={model.name}>{model.name} · {model.provider || 'unknown'}</option>)}</select></label>
          <div className="grid gap-4 sm:grid-cols-2"><NumberField label={t('benchmark.requests')} value={requests} min={1} max={10000} onChange={value => validateNumber(value, 'requests', setRequests)} /><NumberField label={t('benchmark.concurrency')} value={concurrency} min={1} max={256} onChange={value => validateNumber(value, 'concurrency', setConcurrency)} /></div>
          <label className="block"><span className="field-label">{t('benchmark.text')}</span><textarea className="input min-h-32 resize-y" value={text} maxLength={10000} onChange={event => setText(event.target.value)} placeholder={t('benchmark.textPlaceholder')} /><span className="mt-1 block text-right text-[11px] text-slate-400">{text.length}/10000</span></label>
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-5"><div className="text-xs text-slate-400">{LIMITS.requests.min}–{LIMITS.requests.max} {t('benchmark.requests')} · {LIMITS.concurrency.min}–{LIMITS.concurrency.max} {t('benchmark.concurrency')}</div><button type="submit" disabled={!canRun} className="button-primary min-w-36 justify-center">{running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}{running ? t('benchmark.running') : result ? t('benchmark.retry') : t('benchmark.run')}</button></div>
        </form>
      </section>

      <ResultCard result={result} previous={previous} running={running} t={t} />
    </div>

    <section className="surface overflow-hidden"><div className="flex items-center justify-between gap-4 border-b border-slate-100 px-5 py-4 sm:px-6"><div className="flex items-center gap-3"><div className="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-100 text-slate-600"><History className="h-4 w-4" /></div><div><h2 className="text-sm font-semibold text-slate-900">{t('benchmark.history')}</h2><p className="text-xs text-slate-400">{t('benchmark.historySubtitle')}</p></div></div>{history.length > 0 && <span className="text-xs font-medium text-slate-400">{history.length}</span>}</div>{history.length === 0 ? <div className="px-6 py-12 text-center text-sm text-slate-400">{t('benchmark.noHistory')}</div> : <div className="overflow-x-auto"><table className="w-full min-w-[720px] text-left text-xs"><thead className="bg-slate-50/70 text-[10px] uppercase tracking-wider text-slate-400"><tr><th className="px-5 py-3">{t('benchmark.model')}</th><th className="px-5 py-3">{t('benchmark.successRate')}</th><th className="px-5 py-3">{t('benchmark.throughput')}</th><th className="px-5 py-3">{t('benchmark.p95')}</th><th className="px-5 py-3">{t('benchmark.total')}</th><th className="px-5 py-3">{t('benchmark.previous')}</th></tr></thead><tbody className="divide-y divide-slate-100">{history.map(item => <tr key={item.id} className="text-slate-600"><td className="px-5 py-4 font-semibold text-slate-900">{item.model}</td><td className="px-5 py-4 font-mono">{formatPercent(item.successRate)}</td><td className="px-5 py-4 font-mono">{formatNumber(item.requestsPerSecond)}</td><td className="px-5 py-4 font-mono">{formatNumber(item.latencyP95Ms)} {t('benchmark.ms')}</td><td className="px-5 py-4 font-mono">{item.totalRequests}</td><td className="px-5 py-4 text-slate-400">{new Date(item.createdAt).toLocaleString(i18n.language)}</td></tr>)}</tbody></table></div>}</section>
  </div>
}

function NumberField({ label, value, min, max, onChange }: { label: string; value: number; min: number; max: number; onChange: (value: string) => void }) { return <label className="block"><span className="field-label">{label}</span><input className="input" type="number" value={value} min={min} max={max} onChange={event => onChange(event.target.value)} /></label> }

function ResultCard({ result, previous, running, t }: { result: BenchmarkResult | null; previous?: HistoryEntry; running: boolean; t: (key: string, options?: any) => string }) {
  if (running) return <section className="surface flex min-h-[430px] flex-col items-center justify-center p-6 text-center"><Loader2 className="h-8 w-8 animate-spin text-cyan-500" /><p className="mt-4 text-sm font-semibold text-slate-700">{t('benchmark.running')}</p><p className="mt-1 text-xs text-slate-400">{t('benchmark.resultSubtitle')}</p></section>
  if (!result) return <section className="surface flex min-h-[430px] flex-col items-center justify-center p-6 text-center"><div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-100 text-slate-400"><TrendingUp className="h-5 w-5" /></div><p className="mt-4 text-sm font-semibold text-slate-700">{t('benchmark.results')}</p><p className="mt-1 max-w-xs text-xs leading-5 text-slate-400">{t('benchmark.noHistory')}</p></section>
  const metrics: Array<[string, string, string]> = [[t('benchmark.throughput'), `${formatNumber(result.requestsPerSecond)} ${t('benchmark.requestsPerSecond')}`, 'text-cyan-700'], [t('benchmark.average'), `${formatNumber(result.latencyAvgMs)} ${t('benchmark.ms')}`, 'text-slate-900'], [t('benchmark.p50'), `${formatNumber(result.latencyP50Ms)} ${t('benchmark.ms')}`, 'text-slate-900'], [t('benchmark.p95'), `${formatNumber(result.latencyP95Ms)} ${t('benchmark.ms')}`, 'text-slate-900'], [t('benchmark.p99'), `${formatNumber(result.latencyP99Ms)} ${t('benchmark.ms')}`, 'text-slate-900'], [t('benchmark.successRate'), formatPercent(result.successRate), 'text-emerald-700']]
  return <section className="surface overflow-hidden"><div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4 sm:px-6"><div><div className="eyebrow">{t('benchmark.results')}</div><h2 className="mt-1 text-sm font-semibold text-slate-900">{result.model || '—'}</h2><p className="mt-1 text-xs text-slate-400">{t('benchmark.resultSubtitle')}</p></div>{result.failedRequests === 0 ? <CheckCircle2 className="h-5 w-5 text-emerald-500" /> : <XCircle className="h-5 w-5 text-rose-500" />}</div><div className="grid grid-cols-2 gap-3 p-5 sm:grid-cols-3">{metrics.map(([label, value, color]) => <div key={label} className="rounded-xl border border-slate-100 bg-slate-50/60 p-3"><div className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">{label}</div><div className={`mt-2 text-base font-semibold ${color}`}>{value}</div></div>)}</div><div className="grid grid-cols-3 gap-2 px-5 pb-5 text-center text-xs"><Count label={t('benchmark.successful')} value={result.successfulRequests} tone="text-emerald-700" /><Count label={t('benchmark.failed')} value={result.failedRequests} tone="text-rose-700" /><Count label={t('benchmark.total')} value={result.totalRequests} tone="text-slate-700" /></div>{result.failureMessage && <div className="mx-5 mb-5 flex gap-3 rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700"><AlertTriangle className="h-4 w-4 shrink-0" /><div><div className="font-semibold">{t('benchmark.failure')} · {result.failureCode}</div><div className="mt-1">{result.failureMessage}</div></div></div>}{previous && <div className="mx-5 mb-5 rounded-xl border border-cyan-100 bg-cyan-50/70 p-3"><div className="flex items-center gap-2 text-xs font-semibold text-cyan-800"><RotateCcw className="h-3.5 w-3.5" />{t('benchmark.compare')}</div><div className="mt-3 grid grid-cols-3 gap-2 text-[11px] text-cyan-700"><Delta label={t('benchmark.throughput')} value={result.requestsPerSecond - previous.requestsPerSecond} suffix={t('benchmark.requestsPerSecond')} /><Delta label={t('benchmark.p95')} value={result.latencyP95Ms - previous.latencyP95Ms} suffix={t('benchmark.ms')} /><Delta label={t('benchmark.successRate')} value={(result.successRate - previous.successRate) * 100} suffix="pp" /></div></div>}</section>
}

function Count({ label, value, tone }: { label: string; value: number; tone: string }) { return <div className="rounded-xl bg-slate-50 px-2 py-3"><div className={`text-lg font-semibold ${tone}`}>{value}</div><div className="mt-1 text-[10px] text-slate-400">{label}</div></div> }
function Delta({ label, value, suffix }: { label: string; value: number; suffix: string }) { const tone = value > 0 ? 'text-emerald-700' : value < 0 ? 'text-rose-700' : 'text-slate-500'; return <div><div className="text-slate-400">{label}</div><div className={`mt-1 font-mono font-semibold ${tone}`}>{value > 0 ? '+' : ''}{value.toFixed(2)} <span className="font-sans text-[10px] font-normal">{suffix}</span></div></div> }
function formatNumber(value: number) { return Number.isFinite(value) ? value.toFixed(2) : '0.00' }
function formatPercent(value: number) { return `${(Number.isFinite(value) ? value * 100 : 0).toFixed(1)}%` }
