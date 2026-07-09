import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { nlpApi } from '../api/client'
import { Activity, Braces, FileText, GitBranch, Layers3, ListChecks, Play, ScanText, Sparkles } from 'lucide-react'

const TASKS = ['TOK', 'POS', 'NER', 'DEP', 'SDP', 'SRL', 'CON', 'AMR', 'KEYPHRASE', 'EXSUM', 'ABSUM', 'COR', 'CLASSIFICATION', 'SENTIMENT', 'STS', 'TST']
const LANGUAGES = ['zh', 'en', 'ja', 'mul']

const DEFAULT_TEXT = 'HanLP是面向生产环境的自然语言处理工具包。晓美焰来到北京立方庭参观自然语义科技公司。X-NLP 会侧重 NLP 基础能力、检索策略、打分和排序。'
const DEFAULT_PAIR = 'X-NLP 聚焦自然语言处理基础能力和检索排序策略。'

const taskIcon: Record<string, any> = {
  TOK: ScanText,
  POS: ListChecks,
  NER: Sparkles,
  DEP: GitBranch,
  SDP: GitBranch,
  SRL: Activity,
  CON: Layers3,
  AMR: Braces,
  KEYPHRASE: Sparkles,
  EXSUM: FileText,
  ABSUM: FileText,
  COR: ListChecks,
  CLASSIFICATION: Layers3,
  SENTIMENT: Activity,
  STS: Activity,
  TST: Sparkles,
}

export default function NlpWorkbench() {
  const { t } = useTranslation()
  const [task, setTask] = useState('TOK')
  const [language, setLanguage] = useState('zh')
  const [text, setText] = useState(DEFAULT_TEXT)
  const [textPair, setTextPair] = useState(DEFAULT_PAIR)
  const [coarse, setCoarse] = useState(true)
  const [topK, setTopK] = useState(5)
  const [labels, setLabels] = useState('科技, 财经, 体育, 教育')
  const [style, setStyle] = useState('formal')
  const [result, setResult] = useState<any>(null)
  const [error, setError] = useState('')
  const [running, setRunning] = useState(false)

  const selectedTask = useMemo(() => TASKS.find(item => item === task) || TASKS[0], [task])
  const Icon = taskIcon[selectedTask] || ScanText

  const analyze = async () => {
    setRunning(true)
    setError('')
    try {
      const response = await nlpApi.analyze({
        task: selectedTask,
        language,
        text,
        textPair,
        coarse,
        topK,
        labels: labels.split(',').map(item => item.trim()).filter(Boolean),
        style,
      })
      setResult(response)
    } catch (e: any) {
      setError(e.message)
    }
    setRunning(false)
  }

  return (
    <div>
      <div className="mb-6 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-gray-900">{t('nlpWorkbench.title')}</h1>
          <p className="mt-1 max-w-3xl text-sm leading-6 text-gray-500">{t('nlpWorkbench.subtitle')}</p>
        </div>
        <div className="flex w-fit items-center gap-2 rounded-lg border bg-white px-3 py-2 text-xs text-gray-500">
          <Activity className="h-4 w-4 text-emerald-500" />
          {t('nlpWorkbench.runtime')}: {t('nlpWorkbench.demoRuntime')}
        </div>
      </div>

      {error && <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{error}</div>}

      <div className="grid grid-cols-1 gap-5 xl:grid-cols-[280px_minmax(0,1fr)_380px]">
        <aside className="min-w-0 rounded-lg border bg-white">
          <div className="border-b px-4 py-3">
            <h2 className="text-sm font-semibold text-gray-700">{t('nlpWorkbench.allCapabilities')}</h2>
          </div>
          <div className="max-h-[680px] overflow-y-auto p-2">
            {TASKS.map(item => {
              const ItemIcon = taskIcon[item] || ScanText
              const active = item === selectedTask
              return (
                <button
                  key={item}
                  onClick={() => setTask(item)}
                  className={`mb-1 flex w-full min-w-0 items-start gap-3 rounded-md px-3 py-2 text-left transition-colors ${active ? 'bg-gray-900 text-white' : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'}`}
                >
                  <ItemIcon className={`mt-0.5 h-4 w-4 shrink-0 ${active ? 'text-white' : 'text-gray-400'}`} />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">{t(`nlpWorkbench.tasks.${item}.title`)}</span>
                    <span className={`mt-0.5 block text-xs leading-5 ${active ? 'text-gray-200' : 'text-gray-400'}`}>{t(`nlpWorkbench.tasks.${item}.desc`)}</span>
                  </span>
                </button>
              )
            })}
          </div>
        </aside>

        <section className="min-w-0 rounded-lg border bg-white p-4 sm:p-5">
          <div className="mb-4 flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-50 text-blue-600">
              <Icon className="h-5 w-5" />
            </div>
            <div className="min-w-0">
              <h2 className="text-base font-semibold text-gray-900">{t(`nlpWorkbench.tasks.${selectedTask}.title`)}</h2>
              <p className="text-sm text-gray-500">{t(`nlpWorkbench.tasks.${selectedTask}.desc`)}</p>
            </div>
          </div>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <label className="block min-w-0 md:col-span-2">
              <span className="mb-1 block text-xs font-medium text-gray-500">{t('nlpWorkbench.input')}</span>
              <textarea value={text} onChange={event => setText(event.target.value)} rows={8} className="input min-h-44" placeholder={t('nlpWorkbench.inputPlaceholder')} />
            </label>

            {selectedTask === 'STS' && (
              <label className="block min-w-0 md:col-span-2">
                <span className="mb-1 block text-xs font-medium text-gray-500">{t('nlpWorkbench.pairText')}</span>
                <textarea value={textPair} onChange={event => setTextPair(event.target.value)} rows={4} className="input" placeholder={t('nlpWorkbench.pairPlaceholder')} />
              </label>
            )}

            <label className="block min-w-0">
              <span className="mb-1 block text-xs font-medium text-gray-500">{t('nlpWorkbench.language')}</span>
              <select value={language} onChange={event => setLanguage(event.target.value)} className="input">
                {LANGUAGES.map(item => <option key={item} value={item}>{t(`nlpWorkbench.languages.${item}`)}</option>)}
              </select>
            </label>

            <div className="grid grid-cols-2 gap-3">
              <label className="block min-w-0">
                <span className="mb-1 block text-xs font-medium text-gray-500">{t('nlpWorkbench.topK')}</span>
                <input type="number" min={1} max={20} value={topK} onChange={event => setTopK(Number(event.target.value))} className="input" />
              </label>
              <label className="flex items-end gap-2 pb-2 text-sm text-gray-600">
                <input type="checkbox" checked={coarse} onChange={event => setCoarse(event.target.checked)} className="h-4 w-4 rounded border-gray-300" />
                {t('nlpWorkbench.coarse')}
              </label>
            </div>

            {selectedTask === 'CLASSIFICATION' && (
              <label className="block min-w-0 md:col-span-2">
                <span className="mb-1 block text-xs font-medium text-gray-500">{t('nlpWorkbench.labels')}</span>
                <input value={labels} onChange={event => setLabels(event.target.value)} className="input" placeholder={t('nlpWorkbench.labelsPlaceholder')} />
              </label>
            )}

            {selectedTask === 'TST' && (
              <label className="block min-w-0">
                <span className="mb-1 block text-xs font-medium text-gray-500">{t('nlpWorkbench.style')}</span>
                <select value={style} onChange={event => setStyle(event.target.value)} className="input">
                  <option value="formal">{t('nlpWorkbench.formal')}</option>
                  <option value="concise">{t('nlpWorkbench.concise')}</option>
                </select>
              </label>
            )}
          </div>

          <button onClick={analyze} disabled={running || !text.trim()} className="mt-5 flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50">
            <Play className="h-4 w-4" /> {running ? t('nlpWorkbench.analyzing') : t('nlpWorkbench.analyze')}
          </button>
        </section>

        <ResultPanel result={result} />
      </div>
    </div>
  )
}

function ResultPanel({ result }: { result: any }) {
  const { t } = useTranslation()
  const data = result?.result
  return (
    <aside className="min-w-0 rounded-lg border bg-white p-4 sm:p-5">
      <h2 className="mb-4 text-sm font-semibold text-gray-700">{t('nlpWorkbench.result')}</h2>
      {!data ? (
        <p className="rounded-md bg-gray-50 p-4 text-sm leading-6 text-gray-500">{t('nlpWorkbench.noResult')}</p>
      ) : (
        <div className="space-y-4">
          {data.tokens && <TokenList title={t('nlpWorkbench.tokens')} rows={data.tokens} />}
          {data.entities && <EntityList title={t('nlpWorkbench.entities')} rows={data.entities} />}
          {data.arcs && <ArcList title={t('nlpWorkbench.arcs')} rows={data.arcs} />}
          {data.frames && <JsonBlock title={t('nlpWorkbench.frames')} value={data.frames} />}
          {data.keyphrases && <TokenList title={t('nlpWorkbench.keyphrases')} rows={data.keyphrases} />}
          {data.sentences && <TokenList title={t('nlpWorkbench.summary')} rows={data.sentences} />}
          {data.summary && <TextValue title={t('nlpWorkbench.summary')} value={data.summary} />}
          {data.corrected && <TextValue title={t('nlpWorkbench.corrected')} value={data.corrected} />}
          {data.output && <TextValue title={t('nlpWorkbench.output')} value={data.output} />}
          {data.label && <ScoreValue label={data.label} score={data.score} />}
          {data.tree && <JsonBlock title="Tree" value={data.tree} />}
          {data.triples && <JsonBlock title="Triples" value={data.triples} />}
          <JsonBlock title={t('nlpWorkbench.rawJson')} value={result} />
        </div>
      )}
    </aside>
  )
}

function TokenList({ title, rows }: { title: string; rows: any[] }) {
  return (
    <section>
      <h3 className="mb-2 text-xs font-semibold uppercase text-gray-400">{title}</h3>
      <div className="flex flex-wrap gap-2">
        {rows.map((item, index) => (
          <span key={index} className="rounded-md border bg-gray-50 px-2 py-1 text-xs text-gray-700">
            {typeof item === 'string' ? item : `${item.token}/${item.pos}`}
          </span>
        ))}
      </div>
    </section>
  )
}

function EntityList({ title, rows }: { title: string; rows: any[] }) {
  return (
    <section>
      <h3 className="mb-2 text-xs font-semibold uppercase text-gray-400">{title}</h3>
      <div className="space-y-2">
        {rows.map((item, index) => (
          <div key={index} className="flex items-center justify-between gap-3 rounded-md border bg-gray-50 px-3 py-2 text-xs">
            <span className="break-words font-medium text-gray-900">{item.text}</span>
            <span className="shrink-0 rounded-full bg-blue-50 px-2 py-0.5 text-blue-700">{item.type}</span>
          </div>
        ))}
      </div>
    </section>
  )
}

function ArcList({ title, rows }: { title: string; rows: any[] }) {
  return (
    <section>
      <h3 className="mb-2 text-xs font-semibold uppercase text-gray-400">{title}</h3>
      <div className="space-y-2">
        {rows.map((item, index) => (
          <div key={index} className="rounded-md border bg-gray-50 px-3 py-2 text-xs text-gray-700">
            <span className="font-medium text-gray-900">{item.token}</span>
            <span className="mx-2 text-gray-400">{'->'}</span>
            <span>{item.head}</span>
            <span className="ml-2 text-gray-400">{item.relation}</span>
          </div>
        ))}
      </div>
    </section>
  )
}

function ScoreValue({ label, score }: { label: string; score?: number }) {
  const { t } = useTranslation()
  return (
    <section className="rounded-lg border bg-gray-50 p-3">
      <p className="text-xs text-gray-500">{t('nlpWorkbench.label')}</p>
      <p className="mt-1 text-lg font-semibold text-gray-900">{label}</p>
      {score != null && <p className="mt-1 text-xs text-gray-500">{t('nlpWorkbench.score')}: {score}</p>}
    </section>
  )
}

function TextValue({ title, value }: { title: string; value: string }) {
  return (
    <section>
      <h3 className="mb-2 text-xs font-semibold uppercase text-gray-400">{title}</h3>
      <p className="whitespace-pre-wrap break-words rounded-md bg-gray-50 p-3 text-sm leading-6 text-gray-700">{value}</p>
    </section>
  )
}

function JsonBlock({ title, value }: { title: string; value: any }) {
  return (
    <section>
      <h3 className="mb-2 text-xs font-semibold uppercase text-gray-400">{title}</h3>
      <pre className="max-h-72 overflow-auto rounded-md bg-gray-950 p-3 text-xs leading-5 text-gray-100">{JSON.stringify(value, null, 2)}</pre>
    </section>
  )
}
