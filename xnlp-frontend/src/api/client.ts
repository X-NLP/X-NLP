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

// ---- Models ----
export const modelsApi = {
  list: () => request<any[]>('/models'),
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
export const pipelinesApi = {
  capabilities: () => request<any[]>('/pipelines/capabilities'),
  execute: (payload: any) =>
    request<any>('/pipelines/execute', { method: 'POST', body: JSON.stringify(payload) }),
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
