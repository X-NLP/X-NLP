import { FormEvent, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Code2,
  Loader2,
  Play,
  RefreshCw,
  ServerCog,
  Sparkles,
  XCircle,
} from 'lucide-react'
import { modelsApi } from '../api/client'

type ModelInfo = {
  name: string
  type?: string
  provider?: string
  modelName?: string
  protocol?: string
  status?: string
  apiKeySet?: boolean
}

type Diagnostic = {
  name: string
  type?: string
  provider?: string
  model?: string
  endpoint?: string
  configured: boolean
  reachable?: boolean | null
  usable?: boolean | null
  status: string
  httpStatus?: number | null
  elapsedMillis?: number
  failureCode?: string | null
  message?: string | null
  suggestedAction?: string | null
}

function statusTone(status?: string) {
  if (status === 'usable') return 'emerald'
  if (status === 'configured') return 'blue'
  if (status === 'unconfigured') return 'slate'
  return 'amber'
}

function errorMessage(error: unknown) {
  const raw = error instanceof Error ? error.message : String(error)
  const separator = raw.indexOf(': ')
  const body = separator >= 0 ? raw.slice(separator + 2) : raw
  try {
    const parsed = JSON.parse(body)
    return [parsed.message, parsed.error && `error=${parsed.error}`, parsed.requestId && `requestId=${parsed.requestId}`, parsed.traceId && `traceId=${parsed.traceId}`]
      .filter(Boolean)
      .join(' · ') || raw
  } catch {
    return raw
  }
}

export default function Playground() {
  const { t } = useTranslation()
  const [models, setModels] = useState<ModelInfo[]>([])
  const [diagnostics, setDiagnostics] = useState<Diagnostic[]>([])
  const [selected, setSelected] = useState('')
  const [text, setText] = useState('请用一句话介绍 X-NLP 的工程化能力。')
  const [maxLength, setMaxLength] = useState(256)
  const [temperature, setTemperature] = useState(0.2)
  const [loading, setLoading] = useState(true)
  const [running, setRunning] = useState(false)
  const [probing, setProbing] = useState(false)
  const [error, setError] = useState('')
  const [response, setResponse] = useState<any>(null)

  const chatModels = useMemo(
    () => models.filter(model => !model.type || model.type === 'CHAT'),
    [models],
  )
  const selectedModel = chatModels.find(model => model.name === selected)
  const selectedDiagnostic = diagnostics.find(item => item.name === selected)
  const tone = statusTone(selectedDiagnostic?.status)
  const canRun = Boolean(selected && text.trim() && !running)

  async function load() {
    setLoading(true)
    setError('')
    try {
      const [modelList, diagnosticResponse] = await Promise.all([
        modelsApi.list(),
        modelsApi.diagnostics(),
      ])
      const nextModels = Array.isArray(modelList) ? modelList : []
      const nextDiagnostics = Array.isArray(diagnosticResponse?.diagnostics) ? diagnosticResponse.diagnostics : []
      setModels(nextModels)
      setDiagnostics(nextDiagnostics)
      setSelected(current => current && nextModels.some((model: ModelInfo) => model.name === current)
        ? current
        : nextModels.find((model: ModelInfo) => !model.type || model.type === 'CHAT')?.name || '')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])

  async function probe() {
    setProbing(true)
    setError('')
    try {
      const result = await modelsApi.probeDiagnostics()
      setDiagnostics(Array.isArray(result?.diagnostics) ? result.diagnostics : [])
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setProbing(false)
    }
  }

  async function run(event: FormEvent) {
    event.preventDefault()
    if (!canRun) return
    setRunning(true)
    setError('')
    setResponse(null)
    try {
      const result = await modelsApi.predict(selected, text.trim(), maxLength, { temperature })
      setResponse(result)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="space-y-6">
      <section className="surface grid-pattern relative overflow-hidden px-6 py-7 sm:px-8">
        <div className="absolute -right-24 -top-24 h-72 w-72 rounded-full bg-violet-400/15 blur-3xl" />
        <div className="relative flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="max-w-3xl">
            <div className="eyebrow flex items-center gap-2"><Sparkles className="h-3.5 w-3.5" /> {t('playground.eyebrow')}</div>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">{t('playground.title')}</h1>
            <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-500">{t('playground.subtitle')}</p>
          </div>
          <button onClick={() => void probe()} disabled={probing} className="button-secondary shrink-0">
            {probing ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            {t('playground.probe')}
          </button>
        </div>
      </section>

      {error && <div className="flex items-start gap-3 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> <span>{error}</span></div>}

      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <section className="surface overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sm:px-6">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-violet-50 text-violet-600"><Play className="h-4 w-4" /></div>
              <div><h2 className="text-sm font-semibold text-slate-900">{t('playground.runTitle')}</h2><p className="text-xs text-slate-400">{t('playground.runSubtitle')}</p></div>
            </div>
            <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-medium text-slate-500">POST /models/:name/predict</span>
          </div>

          <form onSubmit={run} className="space-y-5 p-5 sm:p-6">
            <label className="block"><span className="field-label">{t('playground.model')}</span>
              <select className="input" value={selected} onChange={event => { setSelected(event.target.value); setResponse(null) }} disabled={loading || chatModels.length === 0}>
                <option value="">{loading ? t('common.loading') : chatModels.length ? t('playground.selectModel') : t('playground.noChatModel')}</option>
                {chatModels.map(model => <option key={model.name} value={model.name}>{model.name} · {model.provider || 'unknown'}</option>)}
              </select>
            </label>

            {selectedModel && <div className="grid gap-3 sm:grid-cols-3">
              <Meta label={t('playground.provider')} value={selectedModel.provider || '—'} />
              <Meta label={t('playground.protocol')} value={selectedModel.protocol || '—'} mono />
              <Meta label={t('playground.runtime')} value={selectedModel.status ? t(`statuses.${selectedModel.status}`, { defaultValue: selectedModel.status }) : t('playground.profileOnly')} />
            </div>}

            <label className="block"><span className="field-label">{t('playground.input')}</span>
              <textarea className="input min-h-[220px] resize-y leading-6" maxLength={32768} value={text} onChange={event => setText(event.target.value)} placeholder={t('playground.inputPlaceholder')} />
              <span className="mt-1 block text-right text-xs text-slate-400">{text.length} / 32768</span>
            </label>

            <div className="grid gap-4 sm:grid-cols-2">
              <label className="block"><span className="field-label">{t('playground.maxLength')}</span><input className="input" type="number" min={1} max={4096} value={maxLength} onChange={event => setMaxLength(Math.max(1, Math.min(4096, Number(event.target.value) || 1)))} /></label>
              <label className="block"><span className="field-label">{t('playground.temperature')} <span className="font-normal text-slate-400">{temperature.toFixed(1)}</span></span><input className="mt-3 w-full accent-violet-600" type="range" min={0} max={2} step={0.1} value={temperature} onChange={event => setTemperature(Number(event.target.value))} /></label>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-5">
              <p className="text-xs text-slate-400">{t('playground.nonStreaming')}</p>
              <button type="submit" disabled={!canRun} className="button-primary min-w-32 justify-center">
                {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
                {running ? t('playground.running') : t('playground.run')}
              </button>
            </div>
          </form>
        </section>

        <aside className="space-y-6">
          <DiagnosticCard diagnostic={selectedDiagnostic} tone={tone} loading={loading} t={t} />
          <section className="surface overflow-hidden">
            <div className="flex items-center gap-3 border-b border-slate-100 px-5 py-4"><Code2 className="h-4 w-4 text-slate-500" /><h2 className="text-sm font-semibold text-slate-900">{t('playground.response')}</h2></div>
            {response ? <div className="space-y-4 p-5">
              <div className="grid grid-cols-2 gap-3"><Meta label={t('playground.responseModel')} value={response.model || selected || '—'} /><Meta label={t('playground.elapsed')} value={`${Number(response.elapsedSeconds || 0).toFixed(3)} s`} /></div>
              <pre className="max-h-[360px] overflow-auto whitespace-pre-wrap rounded-xl bg-slate-950 p-4 text-xs leading-6 text-slate-100">{response.text || t('playground.emptyResponse')}</pre>
              <details><summary className="cursor-pointer text-xs font-semibold text-slate-500">{t('playground.rawResponse')}</summary><pre className="mt-3 overflow-auto rounded-xl bg-slate-50 p-3 text-[11px] text-slate-600">{JSON.stringify(response, null, 2)}</pre></details>
            </div> : <div className="flex min-h-48 flex-col items-center justify-center px-5 text-center"><div className="flex h-11 w-11 items-center justify-center rounded-xl bg-slate-100 text-slate-400"><Code2 className="h-5 w-5" /></div><p className="mt-3 text-sm font-medium text-slate-600">{t('playground.noResponse')}</p><p className="mt-1 text-xs leading-5 text-slate-400">{t('playground.noResponseDescription')}</p></div>}
          </section>
        </aside>
      </div>
    </div>
  )
}

function DiagnosticCard({ diagnostic, tone, loading, t }: { diagnostic?: Diagnostic; tone: string; loading: boolean; t: (key: string, options?: any) => string }) {
  const colors: Record<string, string> = {
    emerald: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    blue: 'border-blue-200 bg-blue-50 text-blue-700',
    amber: 'border-amber-200 bg-amber-50 text-amber-700',
    slate: 'border-slate-200 bg-slate-50 text-slate-600',
  }
  const icon = diagnostic?.status === 'usable' ? <CheckCircle2 className="h-4 w-4" /> : diagnostic?.status === 'unconfigured' ? <XCircle className="h-4 w-4" /> : <AlertTriangle className="h-4 w-4" />
  return <section className={`rounded-2xl border p-5 ${colors[tone] || colors.slate}`}>
    <div className="flex items-center justify-between gap-3"><div className="flex items-center gap-2 text-sm font-semibold"><ServerCog className="h-4 w-4" /> {t('playground.diagnostic')}</div>{loading ? <Loader2 className="h-4 w-4 animate-spin" /> : icon}</div>
    {diagnostic ? <div className="mt-4 space-y-3 text-xs">
      <div className="flex items-center justify-between gap-3"><span className="opacity-70">{t('playground.status')}</span><strong>{t(`diagnosticStatuses.${diagnostic.status}`, { defaultValue: diagnostic.status })}</strong></div>
      <div className="flex items-center justify-between gap-3"><span className="opacity-70">{t('playground.reachability')}</span><strong>{diagnostic.reachable == null ? t('playground.notProbed') : diagnostic.reachable ? t('playground.reachable') : t('playground.unreachable')}</strong></div>
      {diagnostic.endpoint && <div className="truncate"><span className="opacity-70">{t('playground.endpoint')}: </span>{diagnostic.endpoint}</div>}
      {diagnostic.message && <p className="leading-5 opacity-80">{diagnostic.message}</p>}
      {diagnostic.suggestedAction && <p className="border-t border-current/10 pt-3 leading-5"><strong>{t('playground.nextStep')}:</strong> {diagnostic.suggestedAction}</p>}
      {diagnostic.elapsedMillis != null && <div className="flex items-center gap-1 opacity-60"><Clock3 className="h-3.5 w-3.5" /> {diagnostic.elapsedMillis} ms</div>}
    </div> : <p className="mt-3 text-xs leading-5 opacity-70">{t('playground.selectModelForDiagnostic')}</p>}
  </section>
}

function Meta({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return <div className="rounded-xl border border-slate-100 bg-slate-50/70 px-3 py-2.5"><div className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">{label}</div><div className={`mt-1 truncate text-xs font-semibold text-slate-700 ${mono ? 'font-mono' : ''}`}>{value}</div></div>
}
