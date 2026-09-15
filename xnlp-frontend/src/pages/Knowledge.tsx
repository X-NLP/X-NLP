import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertCircle,
  ArrowRight,
  BookOpen,
  Bot,
  CheckCircle2,
  ChevronRight,
  CircleDashed,
  Clock3,
  Database,
  FilePlus2,
  FileText,
  Layers3,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  Send,
  Sparkles,
  TriangleAlert,
  X,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import {
  knowledgeApi,
  type Citation,
  type DocumentIndexStatus,
  type KnowledgeBase,
  type KnowledgeDocument,
  type RagAnswer,
  type RetrievalResult,
} from '../api/client'

type UiError = { code?: string; message: string; requestId?: string; traceId?: string }
type Copy = { [K in keyof typeof copies.en]: string }

const copies = {
  en: {
    eyebrow: 'Grounded AI workspace',
    title: 'Knowledge & RAG',
    subtitle: 'Ingest source documents, inspect every retrieval stage, and validate grounded answers with canonical citations.',
    createBase: 'New knowledge base',
    bases: 'Knowledge bases',
    basesHint: 'Persistent collections and embedding spaces',
    filterBases: 'Filter knowledge bases',
    noBases: 'No knowledge bases yet',
    noBasesDescription: 'Create a collection to start indexing source material for retrieval and grounded generation.',
    createFirst: 'Create your first knowledge base',
    loadingBases: 'Loading knowledge bases',
    loadFailed: 'The knowledge workspace could not be loaded.',
    retry: 'Retry',
    active: 'Active',
    reindexing: 'Reindexing',
    baseError: 'Needs attention',
    documents: 'Documents',
    chunks: 'Chunks',
    embedding: 'Embedding model',
    chunkPolicy: 'Chunk policy',
    updated: 'Updated',
    workspace: 'Knowledge workspace',
    addDocument: 'Import document',
    documentsHint: 'Index lifecycle and failure diagnostics',
    noDocuments: 'No documents indexed',
    noDocumentsDescription: 'Import plain text now. File and URL sources use the same backend lifecycle when supplied by an integration.',
    importFirst: 'Import source text',
    document: 'Document',
    source: 'Source',
    version: 'Version',
    status: 'Index status',
    indexed: 'Indexed',
    indexing: 'Indexing',
    pending: 'Pending',
    failed: 'Failed',
    partialTitle: 'Partial indexing failure',
    partialDescription: '{{failed}} of {{total}} documents failed. Successful documents remain searchable; inspect the failure reason before retrying.',
    allFailedTitle: 'Indexing requires attention',
    allFailedDescription: 'Every document in this knowledge base is currently in a failed state.',
    failureReason: 'Failure reason',
    retrieval: 'Retrieval debugger',
    retrievalHint: 'Compare vector similarity with optional reranking',
    query: 'Search query',
    queryPlaceholder: 'What does the source material say about…',
    topK: 'Top K',
    rerank: 'Rerank results',
    runSearch: 'Run retrieval',
    searching: 'Searching…',
    retrievalEmpty: 'Run a query to inspect evidence, scores, and trace metadata.',
    noMatches: 'No matching chunks passed the current threshold.',
    vectorScore: 'Retrieval',
    rerankScore: 'Rerank',
    result: 'result',
    results: 'results',
    rag: 'Grounded chat',
    ragHint: 'Answers are returned with server-validated citations',
    ask: 'Ask this knowledge base',
    askPlaceholder: 'Ask a question that should be answered from indexed evidence…',
    send: 'Generate answer',
    thinking: 'Retrieving and grounding…',
    ragEmpty: 'Ask a question to see the generated answer, provider metadata, and canonical citations.',
    citations: 'Citations',
    lowConfidence: 'Low confidence',
    providerMissingTitle: 'AI provider is not configured',
    providerMissingDescription: 'Retrieval can still be inspected, but grounded generation requires a Spring AI ChatModel and EmbeddingModel configuration.',
    configureProvider: 'Configure Spring AI provider settings, restart the server, then retry this request.',
    requestFailed: 'The request could not be completed.',
    model: 'Model',
    provider: 'Provider',
    latency: 'Latency',
    trace: 'Trace',
    close: 'Close',
    createTitle: 'Create knowledge base',
    createDescription: 'A knowledge base owns one embedding space and deterministic chunking policy.',
    name: 'Name',
    namePlaceholder: 'Product documentation',
    description: 'Description',
    descriptionPlaceholder: 'What this collection contains and who maintains it',
    embeddingModel: 'Embedding model (optional)',
    embeddingPlaceholder: 'Use the server default when empty',
    cancel: 'Cancel',
    creating: 'Creating…',
    create: 'Create knowledge base',
    importTitle: 'Import source document',
    importDescription: 'Text is submitted to the durable ingestion pipeline and indexed asynchronously.',
    documentTitle: 'Document title',
    documentTitlePlaceholder: 'Architecture overview',
    content: 'Source content',
    contentPlaceholder: 'Paste the source text to index…',
    sourceUri: 'Source URI (optional)',
    sourceUriPlaceholder: 'https://docs.example.com/architecture',
    importing: 'Submitting…',
    import: 'Import and index',
    refresh: 'Refresh',
    loadingDocuments: 'Loading document index state',
    indexedCount: 'Indexed',
    failedCount: 'Failed',
  },
  zh: {
    eyebrow: '可信生成工作台',
    title: '知识库与 RAG',
    subtitle: '导入源文档、检查检索与重排链路，并通过服务端校验的规范引用验证可信回答。',
    createBase: '新建知识库',
    bases: '知识库',
    basesHint: '持久化内容集合与向量空间',
    filterBases: '筛选知识库',
    noBases: '还没有知识库',
    noBasesDescription: '创建一个集合，开始索引用于检索和可信生成的源资料。',
    createFirst: '创建第一个知识库',
    loadingBases: '正在加载知识库',
    loadFailed: '知识工作台加载失败。',
    retry: '重试',
    active: '可用',
    reindexing: '重建索引中',
    baseError: '需要处理',
    documents: '文档',
    chunks: '分块',
    embedding: 'Embedding 模型',
    chunkPolicy: '分块策略',
    updated: '更新时间',
    workspace: '知识工作台',
    addDocument: '导入文档',
    documentsHint: '索引生命周期与失败诊断',
    noDocuments: '尚未索引文档',
    noDocumentsDescription: '现在可直接导入纯文本；文件和 URL 集成也复用同一套后端生命周期。',
    importFirst: '导入源文本',
    document: '文档',
    source: '来源',
    version: '版本',
    status: '索引状态',
    indexed: '已索引',
    indexing: '索引中',
    pending: '等待中',
    failed: '失败',
    partialTitle: '部分文档索引失败',
    partialDescription: '{{total}} 个文档中有 {{failed}} 个失败。成功文档仍可检索，请先查看失败原因再重试。',
    allFailedTitle: '索引需要处理',
    allFailedDescription: '当前知识库中的所有文档均处于失败状态。',
    failureReason: '失败原因',
    retrieval: '检索调试器',
    retrievalHint: '对比向量相似度和可选重排结果',
    query: '检索问题',
    queryPlaceholder: '源资料中如何说明……',
    topK: 'Top K',
    rerank: '启用重排',
    runSearch: '执行检索',
    searching: '检索中…',
    retrievalEmpty: '执行查询后可查看证据、分数和 Trace 元数据。',
    noMatches: '当前阈值下没有匹配的分块。',
    vectorScore: '检索分',
    rerankScore: '重排分',
    result: '条结果',
    results: '条结果',
    rag: '可信问答',
    ragHint: '回答包含服务端校验后的引用',
    ask: '向知识库提问',
    askPlaceholder: '输入一个应基于已索引证据回答的问题……',
    send: '生成回答',
    thinking: '正在检索并生成…',
    ragEmpty: '提问后可查看生成回答、Provider 元数据和规范引用。',
    citations: '引用',
    lowConfidence: '低置信度',
    providerMissingTitle: '尚未配置 AI Provider',
    providerMissingDescription: '仍可调试检索，但可信生成需要配置 Spring AI ChatModel 和 EmbeddingModel。',
    configureProvider: '配置 Spring AI Provider、重启服务后重新发起请求。',
    requestFailed: '请求未能完成。',
    model: '模型',
    provider: 'Provider',
    latency: '耗时',
    trace: 'Trace',
    close: '关闭',
    createTitle: '创建知识库',
    createDescription: '每个知识库拥有独立的 Embedding 空间和确定性分块策略。',
    name: '名称',
    namePlaceholder: '产品文档',
    description: '描述',
    descriptionPlaceholder: '说明集合包含什么内容以及由谁维护',
    embeddingModel: 'Embedding 模型（可选）',
    embeddingPlaceholder: '留空则使用服务端默认配置',
    cancel: '取消',
    creating: '创建中…',
    create: '创建知识库',
    importTitle: '导入源文档',
    importDescription: '文本将提交到可持久化的异步导入流程。',
    documentTitle: '文档标题',
    documentTitlePlaceholder: '架构概览',
    content: '源内容',
    contentPlaceholder: '粘贴需要建立索引的源文本……',
    sourceUri: '来源 URI（可选）',
    sourceUriPlaceholder: 'https://docs.example.com/architecture',
    importing: '提交中…',
    import: '导入并建立索引',
    refresh: '刷新',
    loadingDocuments: '正在加载文档索引状态',
    indexedCount: '已索引',
    failedCount: '失败',
  },
} as const

function parseError(error: unknown, fallback: string): UiError {
  const raw = error instanceof Error ? error.message : String(error)
  const start = raw.indexOf('{')
  if (start >= 0) {
    try {
      const body = JSON.parse(raw.slice(start)) as Record<string, unknown>
      return {
        code: typeof body.error === 'string' ? body.error : undefined,
        message: typeof body.message === 'string' ? body.message : fallback,
        requestId: typeof body.requestId === 'string' ? body.requestId : undefined,
        traceId: typeof body.traceId === 'string' ? body.traceId : undefined,
      }
    } catch {
      // Preserve the transport error below when the response is not valid JSON.
    }
  }
  return { message: raw || fallback }
}

function statusLabel(status: DocumentIndexStatus, copy: Copy) {
  return { PENDING: copy.pending, INDEXING: copy.indexing, INDEXED: copy.indexed, FAILED: copy.failed }[status]
}

function statusTone(status: DocumentIndexStatus) {
  return {
    PENDING: 'border-slate-200 bg-slate-50 text-slate-600',
    INDEXING: 'border-blue-200 bg-blue-50 text-blue-700',
    INDEXED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    FAILED: 'border-red-200 bg-red-50 text-red-700',
  }[status]
}

function Score({ label, value, tone = 'blue' }: { label: string; value?: number; tone?: 'blue' | 'violet' }) {
  if (value == null) return null
  const width = `${Math.max(0, Math.min(100, (value + 1) * 50))}%`
  return (
    <div className="min-w-[112px]">
      <div className="flex items-center justify-between gap-3 text-[11px] font-semibold text-slate-500">
        <span>{label}</span><span className="font-mono text-slate-700">{value.toFixed(4)}</span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100">
        <div className={`h-full rounded-full ${tone === 'violet' ? 'bg-violet-500' : 'bg-blue-500'}`} style={{ width }} />
      </div>
    </div>
  )
}

function Dialog({ title, description, closeLabel, onClose, children }: {
  title: string; description: string; closeLabel: string; onClose: () => void; children: React.ReactNode
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-slate-950/55 p-4 backdrop-blur-sm" onMouseDown={event => event.target === event.currentTarget && onClose()}>
      <section role="dialog" aria-modal="true" aria-labelledby="knowledge-dialog-title" className="surface max-h-[92vh] w-full max-w-xl overflow-y-auto shadow-2xl">
        <div className="flex items-start justify-between border-b border-slate-100 px-5 py-5 sm:px-6">
          <div><h2 id="knowledge-dialog-title" className="text-lg font-semibold text-slate-950">{title}</h2><p className="mt-1 text-sm leading-5 text-slate-500">{description}</p></div>
          <button type="button" onClick={onClose} className="icon-btn" aria-label={closeLabel}><X className="h-5 w-5" /></button>
        </div>
        {children}
      </section>
    </div>
  )
}

export default function Knowledge() {
  const { i18n } = useTranslation()
  const copy: Copy = i18n.resolvedLanguage?.startsWith('zh') ? copies.zh : copies.en
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [selectedId, setSelectedId] = useState('')
  const selectedIdRef = useRef('')
  const searchRequestRef = useRef(0)
  const ragRequestRef = useRef(0)
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [documentsLoading, setDocumentsLoading] = useState(false)
  const [loadError, setLoadError] = useState<UiError | null>(null)
  const [documentsError, setDocumentsError] = useState<UiError | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [topK, setTopK] = useState(5)
  const [rerank, setRerank] = useState(true)
  const [searching, setSearching] = useState(false)
  const [searchResult, setSearchResult] = useState<RetrievalResult | null>(null)
  const [searchError, setSearchError] = useState<UiError | null>(null)
  const [question, setQuestion] = useState('')
  const [asking, setAsking] = useState(false)
  const [ragAnswer, setRagAnswer] = useState<RagAnswer | null>(null)
  const [ragError, setRagError] = useState<UiError | null>(null)

  const selected = bases.find(base => base.id === selectedId)
  const visibleBases = useMemo(() => {
    const normalized = filter.trim().toLowerCase()
    return normalized ? bases.filter(base => `${base.name} ${base.description || ''}`.toLowerCase().includes(normalized)) : bases
  }, [bases, filter])
  const counts = useMemo(() => documents.reduce((result, document) => {
    result[document.indexStatus] += 1
    return result
  }, { PENDING: 0, INDEXING: 0, INDEXED: 0, FAILED: 0 }), [documents])
  const partialFailure = counts.FAILED > 0 && counts.INDEXED > 0
  const providerMissing = ragError?.code?.toLowerCase() === 'provider_unconfigured'

  async function loadBases(preferredId?: string, background = false) {
    if (!background) setLoading(true)
    setLoadError(null)
    try {
      const response = await knowledgeApi.list()
      const items = response.items || response.entries || []
      setBases(items)
      const next = preferredId && items.some(base => base.id === preferredId)
        ? preferredId
        : items.some(base => base.id === selectedId) ? selectedId : items[0]?.id || ''
      setSelectedId(next)
    } catch (error) {
      setLoadError(parseError(error, copy.loadFailed))
    } finally {
      if (!background) setLoading(false)
    }
  }

  async function loadDocuments(baseId = selectedId, background = false) {
    if (!baseId) {
      setDocuments([])
      return
    }
    if (!background) setDocumentsLoading(true)
    setDocumentsError(null)
    try {
      const response = await knowledgeApi.documents(baseId)
      if (baseId === selectedIdRef.current) setDocuments(response.items || response.entries || [])
    } catch (error) {
      if (baseId === selectedIdRef.current) setDocumentsError(parseError(error, copy.requestFailed))
    } finally {
      if (!background && baseId === selectedIdRef.current) setDocumentsLoading(false)
    }
  }

  useEffect(() => { void loadBases() }, [])
  useEffect(() => {
    selectedIdRef.current = selectedId
    searchRequestRef.current += 1
    ragRequestRef.current += 1
    setSearching(false)
    setAsking(false)
    setDocuments([])
    setSearchResult(null)
    setSearchError(null)
    setRagAnswer(null)
    setRagError(null)
    void loadDocuments(selectedId)
  }, [selectedId])

  useEffect(() => {
    if (!selectedId || documentsLoading || !documents.some(document =>
      document.indexStatus === 'PENDING' || document.indexStatus === 'INDEXING')) return
    const timer = window.setTimeout(() => {
      void Promise.all([loadBases(selectedId, true), loadDocuments(selectedId, true)])
    }, 1500)
    return () => window.clearTimeout(timer)
  }, [selectedId, documents, documentsLoading])

  async function runSearch(event: FormEvent) {
    event.preventDefault()
    if (!selected || !query.trim()) return
    const baseId = selected.id
    const requestVersion = ++searchRequestRef.current
    setSearching(true)
    setSearchError(null)
    try {
      const result = await knowledgeApi.search(baseId, {
        query: query.trim(), topK, rerank, rerankTopN: rerank ? topK : undefined,
      })
      if (requestVersion === searchRequestRef.current && baseId === selectedIdRef.current) {
        setSearchResult(result)
      }
    } catch (error) {
      if (requestVersion === searchRequestRef.current && baseId === selectedIdRef.current) {
        setSearchResult(null)
        setSearchError(parseError(error, copy.requestFailed))
      }
    } finally {
      if (requestVersion === searchRequestRef.current && baseId === selectedIdRef.current) {
        setSearching(false)
      }
    }
  }

  async function askKnowledge(event: FormEvent) {
    event.preventDefault()
    if (!selected || !question.trim()) return
    const baseId = selected.id
    const requestVersion = ++ragRequestRef.current
    setAsking(true)
    setRagError(null)
    try {
      const answer = await knowledgeApi.rag(baseId, {
        message: question.trim(), topK, maxContextChunks: Math.min(topK, 8), insufficientContextPolicy: 'ANSWER_WITH_LOW_CONFIDENCE',
      })
      if (requestVersion === ragRequestRef.current && baseId === selectedIdRef.current) {
        setRagAnswer(answer)
      }
    } catch (error) {
      if (requestVersion === ragRequestRef.current && baseId === selectedIdRef.current) {
        setRagAnswer(null)
        setRagError(parseError(error, copy.requestFailed))
      }
    } finally {
      if (requestVersion === ragRequestRef.current && baseId === selectedIdRef.current) {
        setAsking(false)
      }
    }
  }

  return (
    <div className="space-y-6">
      <section className="surface grid-pattern relative overflow-hidden px-6 py-7 sm:px-8">
        <div className="absolute -right-16 -top-20 h-64 w-64 rounded-full bg-cyan-400/15 blur-3xl" />
        <div className="absolute bottom-0 right-1/3 h-28 w-28 rounded-full bg-violet-400/10 blur-3xl" />
        <div className="relative flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="max-w-3xl">
            <div className="eyebrow flex items-center gap-2"><Sparkles className="h-3.5 w-3.5" />{copy.eyebrow}</div>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-950 sm:text-4xl">{copy.title}</h1>
            <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-500">{copy.subtitle}</p>
          </div>
          <button type="button" onClick={() => setCreateOpen(true)} className="button-primary shrink-0"><Plus className="h-4 w-4" />{copy.createBase}</button>
        </div>
      </section>

      {loadError ? (
        <section className="surface flex min-h-[420px] flex-col items-center justify-center p-8 text-center" role="alert">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-red-50 text-red-600"><AlertCircle className="h-6 w-6" /></div>
          <h2 className="mt-4 text-lg font-semibold text-slate-950">{copy.loadFailed}</h2>
          <p className="mt-2 max-w-xl text-sm text-slate-500">{loadError.message}</p>
          <button type="button" onClick={() => void loadBases()} className="button-secondary mt-5"><RefreshCw className="h-4 w-4" />{copy.retry}</button>
        </section>
      ) : loading ? (
        <div className="grid gap-6 xl:grid-cols-[300px_minmax(0,1fr)]" aria-busy="true" aria-label={copy.loadingBases}>
          <div className="surface h-[560px] animate-pulse bg-slate-100" />
          <div className="space-y-6"><div className="surface h-48 animate-pulse bg-slate-100" /><div className="surface h-80 animate-pulse bg-slate-100" /></div>
        </div>
      ) : bases.length === 0 ? (
        <section className="surface flex min-h-[460px] flex-col items-center justify-center p-8 text-center">
          <div className="flex h-16 w-16 items-center justify-center rounded-3xl bg-gradient-to-br from-cyan-100 to-blue-100 text-blue-700"><BookOpen className="h-7 w-7" /></div>
          <h2 className="mt-5 text-xl font-semibold text-slate-950">{copy.noBases}</h2>
          <p className="mt-2 max-w-lg text-sm leading-6 text-slate-500">{copy.noBasesDescription}</p>
          <button type="button" onClick={() => setCreateOpen(true)} className="button-primary mt-6"><Plus className="h-4 w-4" />{copy.createFirst}</button>
        </section>
      ) : (
        <div className="grid min-w-0 gap-6 xl:grid-cols-[300px_minmax(0,1fr)]">
          <aside className="surface h-fit overflow-hidden xl:sticky xl:top-24">
            <div className="border-b border-slate-100 p-5">
              <div className="flex items-center justify-between"><div><h2 className="text-sm font-semibold text-slate-950">{copy.bases}</h2><p className="mt-1 text-xs text-slate-400">{copy.basesHint}</p></div><span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-600">{bases.length}</span></div>
              <label className="relative mt-4 block"><span className="sr-only">{copy.filterBases}</span><Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" /><input value={filter} onChange={event => setFilter(event.target.value)} className="input pl-9" placeholder={copy.filterBases} /></label>
            </div>
            <div className="max-h-[620px] space-y-1 overflow-y-auto p-2">
              {visibleBases.map(base => (
                <button key={base.id} type="button" onClick={() => setSelectedId(base.id)} aria-pressed={base.id === selectedId} className={`w-full rounded-xl p-3 text-left transition ${base.id === selectedId ? 'bg-slate-950 text-white shadow-lg shadow-slate-950/15' : 'text-slate-700 hover:bg-slate-50'}`}>
                  <div className="flex items-start justify-between gap-3"><span className="line-clamp-2 text-sm font-semibold">{base.name}</span><ChevronRight className={`mt-0.5 h-4 w-4 shrink-0 ${base.id === selectedId ? 'text-cyan-300' : 'text-slate-300'}`} /></div>
                  <div className={`mt-2 flex items-center gap-3 text-[11px] ${base.id === selectedId ? 'text-slate-300' : 'text-slate-400'}`}><span>{base.documentCount} {copy.documents.toLowerCase()}</span><span>{base.chunkCount} {copy.chunks.toLowerCase()}</span></div>
                </button>
              ))}
            </div>
          </aside>

          {selected && <main className="min-w-0 space-y-6">
            <section className="surface overflow-hidden">
              <div className="flex flex-col justify-between gap-5 border-b border-slate-100 p-5 sm:flex-row sm:items-start sm:p-6">
                <div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><span className="eyebrow">{copy.workspace}</span><BaseStatus status={selected.status} copy={copy} /></div><h2 className="mt-2 break-words text-2xl font-semibold tracking-tight text-slate-950">{selected.name}</h2>{selected.description && <p className="mt-2 max-w-3xl text-sm leading-6 text-slate-500">{selected.description}</p>}</div>
                <div className="flex shrink-0 gap-2"><button type="button" onClick={() => void Promise.all([loadBases(selected.id, true), loadDocuments(selected.id, true)])} className="button-secondary" aria-label={copy.refresh}><RefreshCw className={`h-4 w-4 ${documentsLoading ? 'animate-spin' : ''}`} />{copy.refresh}</button><button type="button" onClick={() => setImportOpen(true)} className="button-primary"><FilePlus2 className="h-4 w-4" />{copy.addDocument}</button></div>
              </div>
              <dl className="grid gap-px bg-slate-100 sm:grid-cols-2 lg:grid-cols-4">
                <Metric label={copy.documents} value={selected.documentCount} icon={<FileText className="h-4 w-4" />} />
                <Metric label={copy.chunks} value={selected.chunkCount} icon={<Layers3 className="h-4 w-4" />} />
                <Metric label={copy.embedding} value={selected.embeddingModel} icon={<Database className="h-4 w-4" />} compact />
                <Metric label={copy.chunkPolicy} value={`${selected.chunkPolicy.maxCharacters} / ${selected.chunkPolicy.overlapCharacters}`} icon={<CircleDashed className="h-4 w-4" />} compact />
              </dl>
            </section>

            {partialFailure && <StateBanner tone="amber" title={copy.partialTitle} description={copy.partialDescription.replace('{{failed}}', String(counts.FAILED)).replace('{{total}}', String(documents.length))} />}
            {documents.length > 0 && counts.FAILED === documents.length && <StateBanner tone="red" title={copy.allFailedTitle} description={copy.allFailedDescription} />}

            <section className="surface overflow-hidden">
              <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4 sm:px-6"><div><h2 className="text-sm font-semibold text-slate-950">{copy.documents}</h2><p className="mt-1 text-xs text-slate-400">{copy.documentsHint}</p></div><div className="flex gap-2 text-[11px] font-semibold"><span className="rounded-full bg-emerald-50 px-2.5 py-1 text-emerald-700">{copy.indexedCount} {counts.INDEXED}</span>{counts.FAILED > 0 && <span className="rounded-full bg-red-50 px-2.5 py-1 text-red-700">{copy.failedCount} {counts.FAILED}</span>}</div></div>
              {documentsLoading ? <div className="flex min-h-52 items-center justify-center gap-2 text-sm text-slate-500" aria-busy="true"><Loader2 className="h-4 w-4 animate-spin text-blue-500" />{copy.loadingDocuments}</div>
                : documentsError ? <div className="m-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700" role="alert"><p className="font-semibold">{copy.requestFailed}</p><p className="mt-1">{documentsError.message}</p></div>
                  : documents.length === 0 ? <div className="flex min-h-64 flex-col items-center justify-center p-8 text-center"><div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-slate-100 text-slate-500"><FileText className="h-5 w-5" /></div><h3 className="mt-4 text-sm font-semibold text-slate-900">{copy.noDocuments}</h3><p className="mt-2 max-w-lg text-xs leading-5 text-slate-500">{copy.noDocumentsDescription}</p><button type="button" onClick={() => setImportOpen(true)} className="button-secondary mt-4"><FilePlus2 className="h-4 w-4" />{copy.importFirst}</button></div>
                    : <div className="overflow-x-auto"><table className="data-table"><thead><tr><th>{copy.document}</th><th>{copy.source}</th><th>{copy.version}</th><th>{copy.status}</th></tr></thead><tbody>{documents.map(document => <tr key={document.id}><td><div className="max-w-xl"><p className="font-semibold text-slate-800">{document.title}</p><p className="mt-1 line-clamp-2 text-xs leading-5 text-slate-400">{document.content}</p>{document.errorMessage && <div className="mt-2 flex items-start gap-1.5 rounded-lg bg-red-50 px-2.5 py-2 text-xs text-red-700"><TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" /><span><strong>{copy.failureReason}:</strong> {document.errorMessage}</span></div>}</div></td><td><span className="text-xs font-semibold text-slate-600">{document.sourceType}</span>{document.sourceUri && <p className="mt-1 max-w-52 truncate text-xs text-blue-600" title={document.sourceUri}>{document.sourceUri}</p>}</td><td><span className="font-mono text-xs">v{document.version}</span></td><td><span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold ${statusTone(document.indexStatus)}`}>{document.indexStatus === 'INDEXING' && <Loader2 className="h-3 w-3 animate-spin" />}{document.indexStatus === 'INDEXED' && <CheckCircle2 className="h-3 w-3" />}{document.indexStatus === 'FAILED' && <AlertCircle className="h-3 w-3" />}{document.indexStatus === 'PENDING' && <Clock3 className="h-3 w-3" />}{statusLabel(document.indexStatus, copy)}</span></td></tr>)}</tbody></table></div>}
            </section>

            <div className="grid min-w-0 gap-6 2xl:grid-cols-2">
              <section className="surface flex min-h-[520px] min-w-0 flex-col overflow-hidden">
                <PanelTitle icon={<Search className="h-4 w-4" />} title={copy.retrieval} subtitle={copy.retrievalHint} badge="Vector + Rerank" />
                <form onSubmit={runSearch} className="border-b border-slate-100 p-5 sm:p-6"><label className="field-label" htmlFor="knowledge-query">{copy.query}</label><textarea id="knowledge-query" value={query} onChange={event => setQuery(event.target.value)} className="input resize-none" rows={3} placeholder={copy.queryPlaceholder} /><div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-end"><label className="block w-full sm:w-28"><span className="field-label">{copy.topK}</span><input aria-label={copy.topK} type="number" min={1} max={100} value={topK} onChange={event => setTopK(Math.max(1, Math.min(100, Number(event.target.value))))} className="input" /></label><label className="flex min-h-11 flex-1 cursor-pointer items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 text-sm font-medium text-slate-700"><input type="checkbox" checked={rerank} onChange={event => setRerank(event.target.checked)} className="h-4 w-4 rounded border-slate-300 text-blue-600" />{copy.rerank}</label><button type="submit" disabled={searching || !query.trim()} className="button-primary justify-center"><Search className="h-4 w-4" />{searching ? copy.searching : copy.runSearch}</button></div></form>
                <div className="flex-1 overflow-y-auto bg-slate-50/50 p-5 sm:p-6">
                  {searchError ? <ErrorCard error={searchError} fallback={copy.requestFailed} /> : searching ? <LoadingState label={copy.searching} /> : !searchResult ? <EmptyState icon={<Search className="h-5 w-5" />} text={copy.retrievalEmpty} /> : searchResult.matches.length === 0 ? <EmptyState icon={<Search className="h-5 w-5" />} text={copy.noMatches} /> : <div className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500"><span>{searchResult.matches.length} {searchResult.matches.length === 1 ? copy.result : copy.results}</span><span>{searchResult.embeddingModel}{searchResult.reranker ? ` · ${searchResult.reranker}` : ''} · {searchResult.elapsedMs} ms</span></div>{searchResult.matches.map((match, index) => <article key={match.chunkId} className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"><div className="flex items-start gap-3"><span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-slate-950 text-xs font-bold text-white">{index + 1}</span><div className="min-w-0 flex-1"><h3 className="truncate text-sm font-semibold text-slate-900">{match.title}</h3><p className="mt-2 line-clamp-4 text-xs leading-5 text-slate-500">{match.content}</p><div className="mt-4 grid gap-3 sm:grid-cols-2"><Score label={copy.vectorScore} value={match.score} /><Score label={copy.rerankScore} value={match.rerankScore} tone="violet" /></div><p className="mt-3 truncate font-mono text-[10px] text-slate-400">chunk: {match.chunkId}</p></div></div></article>)}</div>}
                </div>
              </section>

              <section className="surface flex min-h-[520px] min-w-0 flex-col overflow-hidden">
                <PanelTitle icon={<Bot className="h-4 w-4" />} title={copy.rag} subtitle={copy.ragHint} badge="Spring AI" />
                <div className="flex-1 overflow-y-auto bg-slate-50/50 p-5 sm:p-6">
                  {providerMissing ? <div className="flex min-h-72 flex-col items-center justify-center text-center" role="status"><div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-amber-100 text-amber-700"><TriangleAlert className="h-6 w-6" /></div><h3 className="mt-4 text-base font-semibold text-slate-950">{copy.providerMissingTitle}</h3><p className="mt-2 max-w-md text-sm leading-6 text-slate-500">{copy.providerMissingDescription}</p><p className="mt-4 max-w-md rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-800">{copy.configureProvider}</p></div>
                    : ragError ? <ErrorCard error={ragError} fallback={copy.requestFailed} /> : asking ? <LoadingState label={copy.thinking} /> : !ragAnswer ? <EmptyState icon={<Sparkles className="h-5 w-5" />} text={copy.ragEmpty} /> : <div className="space-y-5"><article className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"><div className="flex items-start justify-between gap-3"><div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-violet-500 text-white"><Sparkles className="h-4 w-4" /></div>{ragAnswer.lowConfidence && <span className="rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-700">{copy.lowConfidence}</span>}</div><p className="mt-4 whitespace-pre-wrap text-sm leading-7 text-slate-700">{ragAnswer.answer}</p><dl className="mt-5 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-xs sm:grid-cols-4"><Meta label={copy.model} value={ragAnswer.model} /><Meta label={copy.provider} value={ragAnswer.provider} /><Meta label={copy.latency} value={`${ragAnswer.elapsedMs} ms`} /><Meta label={copy.trace} value={ragAnswer.traceId || '—'} /></dl></article><div><h3 className="text-xs font-bold uppercase tracking-[.16em] text-slate-500">{copy.citations} · {ragAnswer.citations.length}</h3><div className="mt-3 space-y-3">{ragAnswer.citations.map((citation, index) => <CitationCard key={`${citation.documentId}-${citation.chunkId}`} citation={citation} index={index} />)}</div></div></div>}
                </div>
                <form onSubmit={askKnowledge} className="border-t border-slate-100 bg-white p-4 sm:p-5"><label className="field-label" htmlFor="knowledge-question">{copy.ask}</label><div className="flex items-end gap-2 rounded-2xl border border-slate-200 bg-slate-50 p-2 focus-within:border-blue-300 focus-within:ring-4 focus-within:ring-blue-500/10"><textarea id="knowledge-question" value={question} onChange={event => setQuestion(event.target.value)} rows={2} className="min-h-[48px] flex-1 resize-none border-0 bg-transparent px-2 py-1.5 text-sm leading-6 outline-none" placeholder={copy.askPlaceholder} /><button type="submit" aria-label={copy.send} disabled={asking || !question.trim()} className="flex h-10 shrink-0 items-center gap-2 rounded-xl bg-slate-950 px-4 text-sm font-semibold text-white transition hover:bg-blue-700 disabled:opacity-40"><Send className="h-4 w-4" /><span className="hidden sm:inline">{copy.send}</span></button></div></form>
              </section>
            </div>
          </main>}
        </div>
      )}

      {createOpen && <CreateKnowledgeDialog copy={copy} onClose={() => setCreateOpen(false)} onCreated={async base => { setCreateOpen(false); await loadBases(base.id) }} />}
      {importOpen && selected && <ImportDocumentDialog copy={copy} base={selected} onClose={() => setImportOpen(false)} onImported={async () => { setImportOpen(false); await Promise.all([loadBases(selected.id, true), loadDocuments(selected.id)]) }} />}
    </div>
  )
}

function BaseStatus({ status, copy }: { status: KnowledgeBase['status']; copy: Copy }) {
  const config = status === 'ACTIVE' ? [copy.active, 'bg-emerald-50 text-emerald-700'] : status === 'REINDEXING' ? [copy.reindexing, 'bg-blue-50 text-blue-700'] : [copy.baseError, 'bg-red-50 text-red-700']
  return <span className={`rounded-full px-2.5 py-1 text-[11px] font-semibold ${config[1]}`}>{config[0]}</span>
}

function Metric({ label, value, icon, compact = false }: { label: string; value: string | number; icon: React.ReactNode; compact?: boolean }) {
  return <div className="bg-white p-5"><div className="flex items-center gap-2 text-xs font-semibold text-slate-400">{icon}{label}</div><dd className={`mt-2 font-semibold text-slate-900 ${compact ? 'truncate text-sm' : 'text-2xl'}`} title={String(value)}>{value}</dd></div>
}

function StateBanner({ tone, title, description }: { tone: 'amber' | 'red'; title: string; description: string }) {
  return <section className={`flex items-start gap-3 rounded-2xl border p-4 ${tone === 'red' ? 'border-red-200 bg-red-50 text-red-800' : 'border-amber-200 bg-amber-50 text-amber-800'}`} role="status"><TriangleAlert className="mt-0.5 h-5 w-5 shrink-0" /><div><h2 className="text-sm font-semibold">{title}</h2><p className="mt-1 text-xs leading-5 opacity-80">{description}</p></div></section>
}

function PanelTitle({ icon, title, subtitle, badge }: { icon: React.ReactNode; title: string; subtitle: string; badge: string }) {
  return <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-5 py-4 sm:px-6"><div className="flex min-w-0 items-center gap-3"><div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600">{icon}</div><div className="min-w-0"><h2 className="text-sm font-semibold text-slate-950">{title}</h2><p className="truncate text-xs text-slate-400">{subtitle}</p></div></div><span className="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-semibold text-slate-500">{badge}</span></div>
}

function EmptyState({ icon, text }: { icon: React.ReactNode; text: string }) {
  return <div className="flex min-h-64 flex-col items-center justify-center text-center"><div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white text-slate-400 shadow-sm ring-1 ring-slate-200">{icon}</div><p className="mt-4 max-w-sm text-sm leading-6 text-slate-500">{text}</p></div>
}

function LoadingState({ label }: { label: string }) {
  return <div className="flex min-h-64 flex-col items-center justify-center" aria-busy="true"><Loader2 className="h-7 w-7 animate-spin text-blue-500" /><p className="mt-3 text-sm text-slate-500">{label}</p></div>
}

function ErrorCard({ error, fallback }: { error: UiError; fallback: string }) {
  return <div className="rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-800" role="alert"><div className="flex items-start gap-2"><AlertCircle className="mt-0.5 h-4 w-4 shrink-0" /><div className="min-w-0"><p className="font-semibold">{fallback}</p><p className="mt-1 break-words text-xs leading-5">{error.message}</p>{(error.code || error.requestId || error.traceId) && <p className="mt-3 break-all font-mono text-[10px] text-red-600">{[error.code && `error=${error.code}`, error.requestId && `requestId=${error.requestId}`, error.traceId && `traceId=${error.traceId}`].filter(Boolean).join(' · ')}</p>}</div></div></div>
}

function Meta({ label, value }: { label: string; value: string }) {
  return <div className="min-w-0"><dt className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</dt><dd className="mt-1 truncate font-medium text-slate-700" title={value}>{value}</dd></div>
}

function CitationCard({ citation, index }: { citation: Citation; index: number }) {
  const sourceUrl = safeExternalUrl(citation.sourceUri)
  return <article className="rounded-2xl border border-slate-200 bg-white p-4"><div className="flex items-start gap-3"><span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-violet-100 text-xs font-bold text-violet-700">{index + 1}</span><div className="min-w-0"><h4 className="text-sm font-semibold text-slate-900">{citation.title}</h4><p className="mt-2 text-xs leading-5 text-slate-500">{citation.excerpt}</p>{sourceUrl && <a href={sourceUrl} target="_blank" rel="noreferrer" className="mt-3 inline-flex max-w-full items-center gap-1 truncate text-xs font-semibold text-blue-600 hover:text-blue-700">{citation.sourceUri}<ArrowRight className="h-3 w-3 shrink-0" /></a>}<p className="mt-2 truncate font-mono text-[10px] text-slate-400">{citation.documentId} / {citation.chunkId}</p></div></div></article>
}

function safeExternalUrl(value?: string) {
  if (!value) return undefined
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : undefined
  } catch {
    return undefined
  }
}

function CreateKnowledgeDialog({ copy, onClose, onCreated }: { copy: Copy; onClose: () => void; onCreated: (base: KnowledgeBase) => Promise<void> }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [embeddingModel, setEmbeddingModel] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<UiError | null>(null)
  async function submit(event: FormEvent) {
    event.preventDefault(); setSubmitting(true); setError(null)
    try { await onCreated(await knowledgeApi.create({ name: name.trim(), description: description.trim() || undefined, embeddingModel: embeddingModel.trim() || undefined })) }
    catch (reason) { setError(parseError(reason, copy.requestFailed)); setSubmitting(false) }
  }
  return <Dialog title={copy.createTitle} description={copy.createDescription} closeLabel={copy.close} onClose={onClose}><form onSubmit={submit} className="space-y-4 p-5 sm:p-6">{error && <ErrorCard error={error} fallback={copy.requestFailed} />}<label className="block"><span className="field-label">{copy.name}</span><input autoFocus required maxLength={120} value={name} onChange={event => setName(event.target.value)} className="input" placeholder={copy.namePlaceholder} /></label><label className="block"><span className="field-label">{copy.description}</span><textarea maxLength={2000} value={description} onChange={event => setDescription(event.target.value)} className="input resize-none" rows={3} placeholder={copy.descriptionPlaceholder} /></label><label className="block"><span className="field-label">{copy.embeddingModel}</span><input maxLength={190} value={embeddingModel} onChange={event => setEmbeddingModel(event.target.value)} className="input" placeholder={copy.embeddingPlaceholder} /></label><div className="flex justify-end gap-2 border-t border-slate-100 pt-4"><button type="button" onClick={onClose} className="button-secondary">{copy.cancel}</button><button type="submit" disabled={submitting || !name.trim()} className="button-primary">{submitting && <Loader2 className="h-4 w-4 animate-spin" />}{submitting ? copy.creating : copy.create}</button></div></form></Dialog>
}

function ImportDocumentDialog({ copy, base, onClose, onImported }: { copy: Copy; base: KnowledgeBase; onClose: () => void; onImported: () => Promise<void> }) {
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [sourceUri, setSourceUri] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<UiError | null>(null)
  async function submit(event: FormEvent) {
    event.preventDefault(); setSubmitting(true); setError(null)
    try { await knowledgeApi.createDocument(base.id, { title: title.trim(), content, sourceType: 'TEXT', sourceUri: sourceUri.trim() || undefined }); await onImported() }
    catch (reason) { setError(parseError(reason, copy.requestFailed)); setSubmitting(false) }
  }
  return <Dialog title={copy.importTitle} description={copy.importDescription} closeLabel={copy.close} onClose={onClose}><form onSubmit={submit} className="space-y-4 p-5 sm:p-6">{error && <ErrorCard error={error} fallback={copy.requestFailed} />}<label className="block"><span className="field-label">{copy.documentTitle}</span><input autoFocus required maxLength={500} value={title} onChange={event => setTitle(event.target.value)} className="input" placeholder={copy.documentTitlePlaceholder} /></label><label className="block"><span className="field-label">{copy.content}</span><textarea required value={content} onChange={event => setContent(event.target.value)} className="input min-h-48 resize-y font-mono text-xs leading-5" placeholder={copy.contentPlaceholder} /></label><label className="block"><span className="field-label">{copy.sourceUri}</span><input maxLength={2048} value={sourceUri} onChange={event => setSourceUri(event.target.value)} className="input" placeholder={copy.sourceUriPlaceholder} /></label><div className="flex justify-end gap-2 border-t border-slate-100 pt-4"><button type="button" onClick={onClose} className="button-secondary">{copy.cancel}</button><button type="submit" disabled={submitting || !title.trim() || !content.trim()} className="button-primary">{submitting && <Loader2 className="h-4 w-4 animate-spin" />}{submitting ? copy.importing : copy.import}</button></div></form></Dialog>
}
