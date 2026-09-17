const BASE = '/api/v1';
const API_KEY = import.meta.env.VITE_XNLP_API_KEY?.trim();
const TENANT_ID = import.meta.env.VITE_XNLP_TENANT_ID?.trim();

const segment = (value: string) => encodeURIComponent(value);

function headers(options?: RequestInit): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    ...(API_KEY ? { 'X-API-Key': API_KEY } : {}),
    ...(TENANT_ID ? { 'X-Tenant-ID': TENANT_ID } : {}),
    ...(options?.headers as Record<string, string> || {}),
  };
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: headers(options),
    ...options,
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${res.status}: ${body}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

async function requestText(path: string, options?: RequestInit): Promise<string> {
  const res = await fetch(`${BASE}${path}`, {
    headers: headers(options),
    ...options,
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${res.status}: ${body}`);
  }
  return res.text();
}

function subscribeToJsonEvents<T>(
  path: string,
  onEvent: (value: T) => void,
  onError?: (error: Error) => void,
): () => void {
  const controller = new AbortController();

  void (async () => {
    try {
      const response = await fetch(`${BASE}${path}`, {
        headers: headers({ headers: { Accept: 'text/event-stream' } }),
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(`${response.status}: ${await response.text()}`);
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (!controller.signal.aborted) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
        const events = buffer.split('\n\n');
        buffer = events.pop() || '';
        for (const event of events) {
          const data = event.split('\n')
            .filter(line => line.startsWith('data:'))
            .map(line => line.slice(5).trimStart())
            .join('\n');
          if (data) onEvent(JSON.parse(data) as T);
        }
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        onError?.(error instanceof Error ? error : new Error(String(error)));
      }
    }
  })();

  return () => controller.abort();
}

function subscribeToPipelineEvents(
  runId: string,
  onEvent: (event: PipelineRunEvent) => void,
  onError?: (error: Error) => void,
  initialLastEventId = 0,
): () => void {
  const controller = new AbortController();
  const delivered = new Set<number>();
  let lastEventId = initialLastEventId;

  const wait = (milliseconds: number) => new Promise<void>(resolve => {
    const timer = window.setTimeout(resolve, milliseconds);
    controller.signal.addEventListener('abort', () => {
      window.clearTimeout(timer);
      resolve();
    }, { once: true });
  });

  void (async () => {
    let reconnectDelay = 750;
    while (!controller.signal.aborted) {
      try {
        const response = await fetch(`${BASE}/pipeline-runs/${segment(runId)}/events`, {
          headers: headers({ headers: {
            Accept: 'text/event-stream',
            'Last-Event-ID': String(lastEventId),
          } }),
          signal: controller.signal,
        });
        if (!response.ok || !response.body) {
          throw new Error(`${response.status}: ${await response.text()}`);
        }

        reconnectDelay = 750;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        while (!controller.signal.aborted) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
          const frames = buffer.split('\n\n');
          buffer = frames.pop() || '';
          for (const frame of frames) {
            let frameId: number | undefined;
            const data: string[] = [];
            for (const line of frame.split('\n')) {
              if (line.startsWith('id:')) frameId = Number(line.slice(3).trim());
              if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
            }
            if (!data.length) continue;
            const event = JSON.parse(data.join('\n')) as PipelineRunEvent;
            const eventId = Number.isFinite(frameId) ? frameId! : event.id;
            if (!Number.isFinite(eventId) || eventId <= lastEventId || delivered.has(eventId)) continue;
            delivered.add(eventId);
            lastEventId = eventId;
            onEvent({ ...event, id: eventId });
          }
        }
      } catch (error) {
        if (controller.signal.aborted) break;
        onError?.(error instanceof Error ? error : new Error(String(error)));
        reconnectDelay = Math.min(reconnectDelay * 2, 10000);
      }
      if (!controller.signal.aborted) await wait(reconnectDelay);
    }
  })();

  return () => controller.abort();
}

// ---- Models ----
export const modelsApi = {
  list: () => request<any[]>('/models'),
  runtime: () => request<any[]>('/models/runtime'),
  get: (name: string) => request<any>(`/models/${segment(name)}`),
  create: (model: any) => request<any>('/models', { method: 'POST', body: JSON.stringify(model) }),
  delete: (name: string) => request<void>(`/models/${segment(name)}`, { method: 'DELETE' }),
  activate: (name: string) => request<any>(`/models/${segment(name)}/activate`, { method: 'POST' }),
  unload: (name: string) => request<void>(`/models/${segment(name)}/unload`, { method: 'POST' }),
  capabilities: () => request<any>('/models/capabilities'),
  diagnostics: () => request<any>('/models/diagnostics'),
  probeDiagnostics: () => request<any>('/models/diagnostics/probe', { method: 'POST' }),
  test: (name: string, payload: any) =>
    request<any>(`/models/${segment(name)}/test`, { method: 'POST', body: JSON.stringify(payload) }),
  predict: (name: string, text: string, maxLength?: number, parameters?: Record<string, any>) =>
    request<any>(`/models/${segment(name)}/predict`, {
      method: 'POST',
      body: JSON.stringify({ text, max_length: maxLength, parameters: parameters || {} }),
    }),
  batchPredict: (name: string, requests: Array<{ text: string; modelName?: string }>) =>
    request<any>(`/models/${segment(name)}/batch-predict`, {
      method: 'POST',
      body: JSON.stringify({ requests }),
    }),
  benchmark: (modelName: string, params?: { requests?: number; concurrency?: number; text?: string }) =>
    request<any>(`/benchmark/${segment(modelName)}`, { method: 'POST', body: JSON.stringify(params || {}) }),
};

// ---- Knowledge & RAG ----
export type KnowledgeBaseStatus = 'ACTIVE' | 'REINDEXING' | 'ERROR';
export type DocumentIndexStatus = 'PENDING' | 'INDEXING' | 'INDEXED' | 'FAILED';

export interface PageResponse<T> {
  items: T[];
  entries?: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
  hasNext: boolean;
}

export interface ChunkPolicy {
  maxCharacters: number;
  overlapCharacters: number;
  separatorMode: 'PARAGRAPH' | 'SENTENCE' | 'FIXED';
}

export interface KnowledgeBase {
  id: string;
  name: string;
  description?: string;
  embeddingModel: string;
  chunkPolicy: ChunkPolicy;
  status: KnowledgeBaseStatus;
  documentCount: number;
  chunkCount: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  externalId?: string;
  title: string;
  sourceType: 'TEXT' | 'FILE' | 'URL' | 'API';
  sourceUri?: string;
  content: string;
  contentChecksum: string;
  version: number;
  indexStatus: DocumentIndexStatus;
  errorMessage?: string;
  metadata: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface RetrievalMatch {
  documentId: string;
  chunkId: string;
  title: string;
  content: string;
  sourceUri?: string;
  score: number;
  rerankScore?: number;
  metadata: Record<string, unknown>;
}

export interface RetrievalResult {
  query: string;
  matches: RetrievalMatch[];
  embeddingModel: string;
  reranker?: string;
  elapsedMs: number;
  traceId?: string;
}

export interface Citation {
  documentId: string;
  chunkId: string;
  title: string;
  sourceUri?: string;
  excerpt: string;
}

export interface RagAnswer {
  answer: string;
  citations: Citation[];
  lowConfidence: boolean;
  retrieval?: RetrievalResult;
  model: string;
  provider: string;
  usage: Record<string, unknown>;
  elapsedMs: number;
  traceId?: string;
}

export const knowledgeApi = {
  list: (query?: string) => {
    const params = new URLSearchParams({ page: '0', size: '200' });
    if (query?.trim()) params.set('query', query.trim());
    return request<PageResponse<KnowledgeBase>>(`/knowledge-bases?${params}`);
  },
  create: (payload: { name: string; description?: string; embeddingModel?: string }) =>
    request<KnowledgeBase>('/knowledge-bases', { method: 'POST', body: JSON.stringify(payload) }),
  documents: (knowledgeBaseId: string) =>
    request<PageResponse<KnowledgeDocument>>(`/knowledge-bases/${segment(knowledgeBaseId)}/documents?page=0&size=200`),
  createDocument: (knowledgeBaseId: string, payload: {
    title: string;
    content: string;
    sourceType: KnowledgeDocument['sourceType'];
    sourceUri?: string;
    externalId?: string;
    metadata?: Record<string, unknown>;
  }) => request<KnowledgeDocument>(`/knowledge-bases/${segment(knowledgeBaseId)}/documents`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  search: (knowledgeBaseId: string, payload: {
    query: string;
    topK?: number;
    minScore?: number;
    rerank?: boolean;
    rerankTopN?: number;
  }) => request<RetrievalResult>(`/knowledge-bases/${segment(knowledgeBaseId)}/search`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  rag: (knowledgeBaseId: string, payload: {
    message: string;
    topK?: number;
    minScore?: number;
    maxContextChunks?: number;
    conversationId?: string;
    insufficientContextPolicy?: 'REJECT' | 'ANSWER_WITH_LOW_CONFIDENCE';
  }) => request<RagAnswer>(`/knowledge-bases/${segment(knowledgeBaseId)}/rag`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
};

// ---- Datasets ----
export const datasetsApi = {
  list: () => request<any[]>('/datasets'),
  get: (id: string) => request<any>(`/datasets/${id}`),
  semanticSearch: (id: string, query: string, topK = 5) =>
    request<any>(`/datasets/${id}/semantic-search`, {
      method: 'POST',
      body: JSON.stringify({ query, topK }),
    }),
  create: (dataset: any) => request<any>('/datasets', { method: 'POST', body: JSON.stringify(dataset) }),
  update: (id: string, dataset: any) =>
    request<any>(`/datasets/${id}`, { method: 'PUT', body: JSON.stringify(dataset) }),
  delete: (id: string) => request<void>(`/datasets/${id}`, { method: 'DELETE' }),
  entries: (id: string, page = 0, size = 50) =>
    request<any>(`/datasets/${id}/entries?page=${page}&size=${size}`),
  exportJson: (id: string) => requestText(`/datasets/${id}/export`),
  count: () => request<{ count: number }>('/datasets/count'),
};

// ---- Evaluations ----
export const evaluationsApi = {
  list: (filters?: { modelName?: string; datasetName?: string; status?: string }) => {
    const params = new URLSearchParams();
    if (filters?.modelName) params.set('modelName', filters.modelName);
    if (filters?.datasetName) params.set('datasetName', filters.datasetName);
    if (filters?.status) params.set('status', filters.status);
    const query = params.toString();
    return request<any[]>(`/evaluations${query ? `?${query}` : ''}`);
  },
  get: (id: string) => request<any>(`/evaluations/${id}`),
  run: (modelName: string, datasetId: string, taskType?: string) =>
    request<any>('/evaluations', {
      method: 'POST',
      body: JSON.stringify({ modelName, datasetId, taskType }),
    }),
  cancel: (id: string) => request<any>(`/evaluations/${id}/cancel`, { method: 'POST' }),
  subscribe: <T>(id: string, onEvent: (run: T) => void, onError?: (error: Error) => void) =>
    subscribeToJsonEvents<T>(`/evaluations/${id}/events`, onEvent, onError),
  compare: (ids: string[]) => {
    const qs = ids.map(id => `ids=${encodeURIComponent(id)}`).join('&');
    return request<any>(`/evaluations/compare?${qs}`);
  },
};

// ---- Pipelines ----
export type PipelineRunStatus =
  | 'queued'
  | 'running'
  | 'cancelling'
  | 'completed'
  | 'failed'
  | 'cancelled';

export type PipelineNodeRunStatus =
  | 'pending'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'timed_out'
  | 'cancelled';

export interface PipelineRunEdge {
  sourceNodeId: string;
  targetNodeId: string;
  sourceOutput?: string | null;
  targetInput?: string | null;
}

export interface PipelineNodeAttempt {
  attempt: number;
  status: PipelineNodeRunStatus | string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
}

export interface PipelineNodeRun {
  nodeId: string;
  capability: string;
  status: PipelineNodeRunStatus | string;
  attempt: number;
  maxAttempts: number;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
}

export interface PipelineRun {
  id: string;
  pipelineId: string;
  pipelineVersion: number;
  status: PipelineRunStatus | string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  cancelRequested: boolean;
  nodes: PipelineNodeRun[];
  edges?: PipelineRunEdge[];
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface PipelineRunEvent {
  id: number;
  runId: string;
  type: string;
  status?: string | null;
  nodeId?: string | null;
  attempt?: number | null;
  detail: Record<string, unknown>;
  occurredAt: string;
}

export interface PipelineNodeTrace {
  nodeId: string;
  capability: string;
  status: PipelineNodeRunStatus | string;
  attempts: PipelineNodeAttempt[];
  output: Record<string, unknown>;
  errorCode?: string | null;
  errorMessage?: string | null;
}

export interface PipelineTrace {
  traceId: string;
  runId: string;
  pipelineId: string;
  pipelineVersion: number;
  status: PipelineRunStatus | string;
  startedAt?: string | null;
  completedAt?: string | null;
  durationMs?: number | null;
  nodes: PipelineNodeTrace[];
  events: PipelineRunEvent[];
}

export interface PipelineRunFilters {
  status?: string;
  pipelineId?: string;
}

export const pipelinesApi = {
  capabilities: () => request<any[]>('/pipelines/capabilities'),
  execute: (payload: any) =>
    request<any>('/pipelines/execute', { method: 'POST', body: JSON.stringify(payload) }),
  listRuns: async (filters?: PipelineRunFilters) => {
    const params = new URLSearchParams();
    if (filters?.status) params.set('status', filters.status);
    if (filters?.pipelineId) params.set('pipelineId', filters.pipelineId);
    const query = params.toString();
    const response = await request<PipelineRun[] | PageResponse<PipelineRun>>(
      `/pipeline-runs${query ? `?${query}` : ''}`,
    );
    return Array.isArray(response) ? response : response.items ?? response.entries ?? [];
  },
  getRun: (runId: string) => request<PipelineRun>(`/pipeline-runs/${segment(runId)}`),
  cancelRun: (runId: string) => request<PipelineRun>(`/pipeline-runs/${segment(runId)}/cancel`, {
    method: 'POST',
  }),
  subscribeToRunEvents: (
    runId: string,
    onEvent: (event: PipelineRunEvent) => void,
    onError?: (error: Error) => void,
    lastEventId = 0,
  ) => subscribeToPipelineEvents(runId, onEvent, onError, lastEventId),
  trace: (runId: string) => request<PipelineTrace>(
    `/pipeline-runs/${segment(runId)}/trace?format=json`,
    { headers: { Accept: 'application/json' } },
  ),
};

// ---- NLP Tasks ----
export const nlpApi = {
  tasks: () => request<any[]>('/nlp/tasks'),
  analyze: (payload: any) =>
    request<any>('/nlp/analyze', { method: 'POST', body: JSON.stringify(payload) }),
  semanticSimilarity: (text: string, textPair: string) =>
    request<any>('/nlp/semantic-similarity', {
      method: 'POST',
      body: JSON.stringify({ text, textPair }),
    }),
  classify: (modelName: string, text: string, categories: string[]) =>
    request<any>('/nlp/classify', { method: 'POST', body: JSON.stringify({ modelName, text, categories }) }),
  sentiment: (modelName: string, text: string) =>
    request<any>('/nlp/sentiment', { method: 'POST', body: JSON.stringify({ modelName, text }) }),
  summarize: (modelName: string, text: string, maxLength?: number) =>
    request<any>('/nlp/summarize', { method: 'POST', body: JSON.stringify({ modelName, text, maxLength }) }),
  ner: (modelName: string, text: string) =>
    request<any>('/nlp/ner', { method: 'POST', body: JSON.stringify({ modelName, text }) }),
  qa: (modelName: string, context: string, question: string) =>
    request<any>('/nlp/qa', { method: 'POST', body: JSON.stringify({ modelName, context, question }) }),
  translate: (modelName: string, text: string, sourceLanguage?: string) =>
    request<any>('/nlp/translate', { method: 'POST', body: JSON.stringify({ modelName, text, sourceLanguage }) }),
};

// ---- Spring AI ----
export const aiApi = {
  status: () => request<any>('/ai/status'),
  chat: (message: string, context?: string, modelName?: string) =>
    request<any>('/ai/chat', { method: 'POST', body: JSON.stringify({ message, context, modelName }) }),
};

// ---- Health ----
export const healthApi = {
  check: async () => {
    const res = await fetch('/health', { headers: headers() });
    if (!res.ok) throw new Error(`${res.status}: ${await res.text()}`);
    return res.json();
  },
};
