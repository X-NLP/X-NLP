const BASE = '/api/v1';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers as Record<string, string> || {}) },
    ...options,
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`${res.status}: ${body}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// ---- Models ----
export const modelsApi = {
  list: () => request<any[]>('/models'),
  get: (name: string) => request<any>(`/models/${name}`),
  create: (model: any) => request<any>('/models', { method: 'POST', body: JSON.stringify(model) }),
  delete: (name: string) => request<void>(`/models/${name}`, { method: 'DELETE' }),
  activate: (name: string) => request<any>(`/models/${name}/activate`, { method: 'POST' }),
  unload: (name: string) => request<void>(`/models/${name}/unload`, { method: 'POST' }),
  capabilities: () => request<any>('/models/capabilities'),
  test: (name: string, payload: any) =>
    request<any>(`/models/${name}/test`, { method: 'POST', body: JSON.stringify(payload) }),
  predict: (name: string, text: string) =>
    request<any>(`/models/${name}/predict`, { method: 'POST', body: JSON.stringify({ text }) }),
  benchmark: (modelName: string, params?: Record<string, any>) =>
    request<any>(`/benchmark/${modelName}`, { method: 'POST', body: JSON.stringify(params || {}) }),
};

// ---- Datasets ----
export const datasetsApi = {
  list: () => request<any[]>('/datasets'),
  get: (id: string) => request<any>(`/datasets/${id}`),
  create: (dataset: any) => request<any>('/datasets', { method: 'POST', body: JSON.stringify(dataset) }),
  update: (id: string, dataset: any) =>
    request<any>(`/datasets/${id}`, { method: 'PUT', body: JSON.stringify(dataset) }),
  delete: (id: string) => request<void>(`/datasets/${id}`, { method: 'DELETE' }),
  entries: (id: string, page = 0, size = 50) =>
    request<any>(`/datasets/${id}/entries?page=${page}&size=${size}`),
  exportJson: (id: string) => request<string>(`/datasets/${id}/export`),
  count: () => request<{ count: number }>('/datasets/count'),
};

// ---- Evaluations ----
export const evaluationsApi = {
  list: () => request<any[]>('/evaluations'),
  get: (id: string) => request<any>(`/evaluations/${id}`),
  run: (modelName: string, datasetId: string, taskType?: string) =>
    request<any>('/evaluations', {
      method: 'POST',
      body: JSON.stringify({ modelName, datasetId, taskType }),
    }),
  compare: (ids: string[]) => {
    const qs = ids.map(id => `ids=${encodeURIComponent(id)}`).join('&');
    return request<any>(`/evaluations/compare?${qs}`);
  },
};

// ---- NLP Tasks ----
export const nlpApi = {
  tasks: () => request<any[]>('/nlp/tasks'),
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

// ---- Health ----
export const healthApi = {
  check: () => request<any>('/../health'),
};
