import { type ReactNode, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { datasetsApi, evaluationsApi } from '../api/client'
import {
  ArrowRight,
  BarChart3,
  Braces,
  CheckCircle2,
  Database,
  FileText,
  Gauge,
  GitBranch,
  PlayCircle,
  RefreshCw,
  Sparkles,
} from 'lucide-react'

type NodeStatus = 'ready' | 'active' | 'empty' | 'warning'

type CanvasNode = {
  id: string
  title: string
  subtitle: string
  icon: any
  status: NodeStatus
  metric: string
  detail: string
  changes: string[]
}

const statusClasses: Record<NodeStatus, string> = {
  ready: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  active: 'border-blue-200 bg-blue-50 text-blue-700',
  empty: 'border-gray-200 bg-gray-50 text-gray-500',
  warning: 'border-amber-200 bg-amber-50 text-amber-700',
}

const metricNames = [
  'accuracy',
  'f1Macro',
  'precisionMacro',
  'recallMacro',
  'rouge1',
  'rougeL',
  'bleu',
  'exactMatch',
  'entityF1',
]

export default function Canvas() {
  const { t } = useTranslation()
  const [datasets, setDatasets] = useState<any[]>([])
  const [evaluations, setEvaluations] = useState<any[]>([])
  const [entries, setEntries] = useState<any[]>([])
  const [datasetId, setDatasetId] = useState('')
  const [runId, setRunId] = useState('')
  const [selectedEntry, setSelectedEntry] = useState(0)
  const [selectedNode, setSelectedNode] = useState('raw')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const [datasetList, runList] = await Promise.all([datasetsApi.list(), evaluationsApi.list()])
      setDatasets(datasetList)
      setEvaluations(runList)
      const nextDatasetId = datasetId || datasetList[0]?.id || ''
      const nextRunId = runId || runList.find((run: any) => run.status === 'completed')?.id || runList[0]?.id || ''
      setDatasetId(nextDatasetId)
      setRunId(nextRunId)
      if (nextDatasetId) {
        const data = await datasetsApi.entries(nextDatasetId, 0, 20)
        setEntries(data.entries || [])
      } else {
        setEntries([])
      }
    } catch (e: any) {
      setError(e.message || t('canvas.loadDataError'))
    }
    setLoading(false)
  }

  useEffect(() => { load() }, [])

  useEffect(() => {
    if (!datasetId) { setEntries([]); return }
    datasetsApi.entries(datasetId, 0, 20)
      .then(data => { setEntries(data.entries || []); setSelectedEntry(0) })
      .catch((e: any) => setError(e.message || t('canvas.loadEntriesError')))
  }, [datasetId])

  const dataset = datasets.find(item => item.id === datasetId)
  const run = evaluations.find(item => item.id === runId)
  const entry = entries[selectedEntry]
  const normalized = normalizeText(entry?.input || '')
  const prompt = buildPrompt(t, dataset?.taskType || run?.taskType, normalized)
  const metricSummary = summarizeMetrics(t, run?.metrics)
  const nodes = useMemo(
    () => buildNodes(t, dataset, run, entry, normalized, prompt, metricSummary),
    [t, dataset, run, entry, normalized, prompt, metricSummary]
  )
  const activeNode = nodes.find(node => node.id === selectedNode) || nodes[0]

  const filteredRuns = datasetId
    ? evaluations.filter(run => run.datasetId === datasetId)
    : evaluations

  useEffect(() => {
    if (!filteredRuns.some(run => run.id === runId)) {
      setRunId(filteredRuns.find(run => run.status === 'completed')?.id || filteredRuns[0]?.id || '')
    }
  }, [datasetId, evaluations])

  return (
    <div>
      <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">{t('canvas.title')}</h1>
          <p className="mt-1 text-sm text-gray-500">{t('canvas.subtitle')}</p>
        </div>
        <button
          onClick={load}
          className="flex w-fit items-center gap-2 rounded-lg border px-3 py-2 text-sm text-gray-600 hover:bg-gray-50"
        >
          <RefreshCw className="h-4 w-4" /> {t('canvas.refresh')}
        </button>
      </div>

      {error && <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{error}</div>}

      <div className="mb-5 grid grid-cols-1 gap-4 md:grid-cols-3">
        <SelectField label={t('canvas.dataset')} value={datasetId} onChange={setDatasetId} disabled={loading || datasets.length === 0}>
          <option value="">{t('canvas.noDataset')}</option>
          {datasets.map((item: any) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </SelectField>
        <SelectField label={t('canvas.evaluationRun')} value={runId} onChange={setRunId} disabled={filteredRuns.length === 0}>
          <option value="">{t('canvas.noRun')}</option>
          {filteredRuns.map((item: any) => (
            <option key={item.id} value={item.id}>{item.modelName} / {t(`statuses.${item.status}`, { defaultValue: item.status })}</option>
          ))}
        </SelectField>
        <SelectField
          label={t('canvas.entrySample')}
          value={String(selectedEntry)}
          onChange={value => setSelectedEntry(Number(value))}
          disabled={entries.length === 0}
        >
          {entries.length === 0 ? <option value="0">{t('canvas.noEntry')}</option> : entries.map((item: any, index: number) => (
            <option key={item.id || index} value={index}>#{index + 1} {truncate(item.input, 48)}</option>
          ))}
        </SelectField>
      </div>

      <div className="mb-5 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <SummaryTile label={t('canvas.task')} value={t(`tasks.${dataset?.taskType || run?.taskType}`, { defaultValue: dataset?.taskType || run?.taskType || '-' })} icon={GitBranch} />
        <SummaryTile label={t('canvas.entries')} value={String(dataset?.entryCount ?? entries.length ?? 0)} icon={Database} />
        <SummaryTile label={t('canvas.runStatus')} value={run?.status ? t(`statuses.${run.status}`, { defaultValue: run.status }) : '-'} icon={PlayCircle} />
        <SummaryTile label={t('canvas.bestSignal')} value={metricSummary.primary} icon={Gauge} />
      </div>

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
        <section className="min-w-0 rounded-lg border bg-white">
          <div className="border-b px-4 py-3">
            <h2 className="text-sm font-semibold text-gray-700">{t('canvas.processingCanvas')}</h2>
          </div>
          <div className="overflow-x-auto p-4">
            <div className="grid min-w-[920px] grid-cols-[repeat(6,142px)] items-stretch gap-3">
              {nodes.map((node, index) => (
                <div key={node.id} className="flex items-stretch gap-3">
                  <button
                    onClick={() => setSelectedNode(node.id)}
                    className={`flex w-[142px] flex-col rounded-lg border p-3 text-left transition ${selectedNode === node.id ? 'border-gray-900 shadow-sm' : 'border-gray-200 hover:border-gray-300'}`}
                  >
                    <div className={`mb-3 flex h-9 w-9 items-center justify-center rounded-lg border ${statusClasses[node.status]}`}>
                      <node.icon className="h-4 w-4" />
                    </div>
                    <span className="text-sm font-semibold text-gray-900">{node.title}</span>
                    <span className="mt-1 min-h-8 text-xs leading-4 text-gray-500">{node.subtitle}</span>
                    <span className="mt-auto pt-3 text-xs font-medium text-gray-700">{node.metric}</span>
                  </button>
                  {index < nodes.length - 1 && (
                    <div className="flex w-5 items-center justify-center text-gray-300">
                      <ArrowRight className="h-4 w-4" />
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </section>

        <aside className="min-w-0 rounded-lg border bg-white p-4">
          <div className="mb-4 flex items-center gap-2">
            <div className={`flex h-9 w-9 items-center justify-center rounded-lg border ${statusClasses[activeNode.status]}`}>
              <activeNode.icon className="h-4 w-4" />
            </div>
            <div className="min-w-0">
              <h2 className="text-sm font-semibold text-gray-900">{activeNode.title}</h2>
              <p className="text-xs text-gray-500">{activeNode.subtitle}</p>
            </div>
          </div>
          <p className="mb-4 break-words rounded-md bg-gray-50 p-3 text-sm leading-6 text-gray-700">{activeNode.detail}</p>
          <div className="space-y-2">
            {activeNode.changes.map((change, index) => (
              <div key={index} className="flex gap-2 text-sm text-gray-600">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
                <span className="break-words">{change}</span>
              </div>
            ))}
          </div>
        </aside>
      </div>

      <div className="mt-5 grid grid-cols-1 gap-5 lg:grid-cols-2">
        <TextPanel title={t('canvas.entryInput')} icon={FileText} text={entry?.input || t('canvas.selectEntry')} />
        <TextPanel title={t('canvas.referenceOutput')} icon={Braces} text={entry?.expectedOutput || t('canvas.noReference')} />
      </div>
    </div>
  )
}

function buildNodes(t: any, dataset: any, run: any, entry: any, normalized: string, prompt: string, metricSummary: { primary: string; details: string[] }): CanvasNode[] {
  const input = entry?.input || ''
  const expected = entry?.expectedOutput || ''
  const whitespaceDelta = input.length - normalized.length
  const taskType = dataset?.taskType || run?.taskType || ''
  return [
    {
      id: 'raw',
      title: t('canvas.nodeTitles.raw'),
      subtitle: t('canvas.nodeSubtitles.raw'),
      icon: FileText,
      status: input ? 'ready' : 'empty',
      metric: input ? t('canvas.chars', { count: input.length }) : t('canvas.noInput'),
      detail: input || t('canvas.noEntrySelected'),
      changes: [
        t('canvas.changes.dataset', { value: dataset?.name || '-' }),
        t('canvas.changes.entryId', { value: entry?.id || '-' }),
        t('canvas.changes.metadataKeys', { count: Object.keys(entry?.metadata || {}).length }),
      ],
    },
    {
      id: 'normalize',
      title: t('canvas.nodeTitles.normalize'),
      subtitle: t('canvas.nodeSubtitles.normalize'),
      icon: Sparkles,
      status: input ? 'active' : 'empty',
      metric: whitespaceDelta === 0 ? t('canvas.noTrimDelta') : `-${t('canvas.chars', { count: whitespaceDelta })}`,
      detail: normalized || t('canvas.noNormalizedText'),
      changes: [
        t('canvas.changes.trim'),
        t('canvas.changes.collapseWhitespace'),
        t('canvas.changes.outputLength', { count: normalized.length }),
      ],
    },
    {
      id: 'labels',
      title: t('canvas.nodeTitles.labels'),
      subtitle: t('canvas.nodeSubtitles.labels'),
      icon: Braces,
      status: expected ? 'ready' : 'warning',
      metric: expected ? t('canvas.chars', { count: expected.length }) : t('canvas.missing'),
      detail: expected || t('canvas.noExpectedOutput'),
      changes: [
        t('canvas.changes.taskType', { value: taskType ? t(`tasks.${taskType}`, { defaultValue: taskType }) : '-' }),
        t('canvas.changes.labelKeys', { count: Object.keys(entry?.labels || {}).length }),
        t('canvas.changes.referenceSide'),
      ],
    },
    {
      id: 'prompt',
      title: t('canvas.nodeTitles.prompt'),
      subtitle: t('canvas.nodeSubtitles.prompt'),
      icon: GitBranch,
      status: prompt ? 'active' : 'empty',
      metric: prompt ? t('canvas.chars', { count: prompt.length }) : t('canvas.noPrompt'),
      detail: prompt || t('canvas.selectTaskForPrompt'),
      changes: [
        taskType ? t(`canvas.prompts.${taskType}`, { defaultValue: t('canvas.genericTaskFraming') }) : t('canvas.genericTaskFraming'),
        t('canvas.changes.inputAfterInstruction'),
        t('canvas.changes.referenceNotIncluded'),
      ],
    },
    {
      id: 'run',
      title: t('canvas.nodeTitles.run'),
      subtitle: t('canvas.nodeSubtitles.run'),
      icon: PlayCircle,
      status: run?.status === 'completed' ? 'ready' : run ? 'warning' : 'empty',
      metric: run?.elapsedSeconds != null ? `${run.elapsedSeconds.toFixed(1)}s` : t('canvas.noRun'),
      detail: run ? t('canvas.runDetail', { model: run.modelName, dataset: run.datasetName }) : t('canvas.noRunSelected'),
      changes: [
        t('canvas.changes.model', { value: run?.modelName || '-' }),
        t('canvas.changes.status', { value: run?.status ? t(`statuses.${run.status}`, { defaultValue: run.status }) : '-' }),
        run?.errorMessage ? `${t('common.error')}: ${run.errorMessage}` : t('canvas.changes.noRunError'),
      ],
    },
    {
      id: 'metrics',
      title: t('canvas.nodeTitles.metrics'),
      subtitle: t('canvas.nodeSubtitles.metrics'),
      icon: BarChart3,
      status: run?.metrics ? 'ready' : 'empty',
      metric: metricSummary.primary,
      detail: run?.metrics ? t('canvas.metricsDetail') : t('canvas.runHasNoMetrics'),
      changes: metricSummary.details,
    },
  ]
}

function normalizeText(value: string) {
  return value.trim().replace(/\s+/g, ' ')
}

function buildPrompt(t: any, taskType: string, input: string) {
  if (!taskType || !input) return ''
  const task = t(`canvas.prompts.${taskType}`, { defaultValue: t('canvas.prompts.generic') })
  return `${task}\n\n${t('common.input')}: ${input}`
}

function summarizeMetrics(t: any, metrics: any) {
  if (!metrics) return { primary: t('canvas.noMetrics'), details: [t('canvas.runEvaluationHint')] }
  const details = metricNames
    .map(key => [t(`metrics.${key}`, { defaultValue: key }), metrics[key]] as [string, number | undefined])
    .filter(([, value]) => value != null)
    .map(([label, value]) => `${label}: ${formatMetric(value)}`)
  return {
    primary: details[0]?.replace(': ', ' ') || t('canvas.metricsReady'),
    details: details.length ? details : [t('canvas.noDisplayableMetrics')],
  }
}

function formatMetric(value?: number) {
  if (value == null) return '-'
  return value <= 1 ? `${(value * 100).toFixed(1)}%` : value.toFixed(4)
}

function truncate(value: string, max: number) {
  if (!value) return ''
  return value.length > max ? `${value.slice(0, max - 1)}...` : value
}

function SelectField({ label, value, onChange, disabled, children }: {
  label: string
  value: string
  onChange: (value: string) => void
  disabled?: boolean
  children: ReactNode
}) {
  return (
    <label className="block min-w-0">
      <span className="mb-1 block text-xs font-medium text-gray-500">{label}</span>
      <select
        value={value}
        onChange={event => onChange(event.target.value)}
        disabled={disabled}
        className="w-full min-w-0 rounded-md border px-3 py-2 text-sm disabled:bg-gray-50 disabled:text-gray-400"
      >
        {children}
      </select>
    </label>
  )
}

function SummaryTile({ label, value, icon: Icon }: { label: string; value: string; icon: any }) {
  return (
    <div className="min-w-0 rounded-lg border bg-white p-4">
      <div className="mb-3 flex h-9 w-9 items-center justify-center rounded-lg bg-gray-100 text-gray-600">
        <Icon className="h-4 w-4" />
      </div>
      <p className="text-xs text-gray-500">{label}</p>
      <p className="mt-1 break-words text-sm font-semibold text-gray-900">{value}</p>
    </div>
  )
}

function TextPanel({ title, text, icon: Icon }: { title: string; text: string; icon: any }) {
  return (
    <section className="min-w-0 rounded-lg border bg-white p-4">
      <div className="mb-3 flex items-center gap-2">
        <Icon className="h-4 w-4 text-gray-500" />
        <h2 className="text-sm font-semibold text-gray-700">{title}</h2>
      </div>
      <p className="max-h-64 overflow-y-auto whitespace-pre-wrap break-words rounded-md bg-gray-50 p-3 text-sm leading-6 text-gray-700">{text}</p>
    </section>
  )
}
