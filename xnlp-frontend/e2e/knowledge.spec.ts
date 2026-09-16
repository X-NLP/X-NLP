import { expect, test, type Page, type Route } from '@playwright/test'

const base = {
  id: 'kb-product', name: 'Product Knowledge', description: 'Release and architecture documentation.',
  embeddingModel: 'text-embedding-3-small',
  chunkPolicy: { maxCharacters: 1200, overlapCharacters: 200, separatorMode: 'PARAGRAPH' },
  status: 'ACTIVE', documentCount: 1, chunkCount: 3,
  createdAt: '2026-09-15T09:00:00Z', updatedAt: '2026-09-15T09:05:00Z',
}
const document = {
  id: 'doc-release', knowledgeBaseId: base.id, title: 'Release architecture', sourceType: 'TEXT',
  sourceUri: 'https://docs.example.com/releases/0.4',
  content: 'Release 0.4 adds portable JDBC vector storage and grounded RAG citations.',
  contentChecksum: 'a'.repeat(64), version: 1, indexStatus: 'INDEXED', metadata: {},
  createdAt: '2026-09-15T09:04:00Z', updatedAt: '2026-09-15T09:05:00Z',
}
const pageOf = <T>(items: T[]) => ({ items, entries: items, page: 0, size: 200, total: items.length, totalPages: items.length ? 1 : 0, hasNext: false })

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('xnlp.language', 'en'))
  await page.route('**/health', route => json(route, { status: 'UP' }))
})

test('creates a knowledge base, imports text, retrieves evidence, and renders canonical RAG citations', async ({ page }) => {
  let bases: typeof base[] = []
  let documents: typeof document[] = []
  let createPayload: Record<string, unknown> | undefined
  let importPayload: Record<string, unknown> | undefined
  let searchPayload: Record<string, unknown> | undefined
  let ragPayload: Record<string, unknown> | undefined

  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/knowledge-bases') return json(route, pageOf(bases))
    if (request.method() === 'POST' && path === '/api/v1/knowledge-bases') {
      createPayload = request.postDataJSON(); bases = [base]; return json(route, base, 201)
    }
    if (request.method() === 'GET' && path === `/api/v1/knowledge-bases/${base.id}/documents`) return json(route, pageOf(documents))
    if (request.method() === 'POST' && path === `/api/v1/knowledge-bases/${base.id}/documents`) {
      importPayload = request.postDataJSON(); documents = [document]; return json(route, document, 202)
    }
    if (request.method() === 'POST' && path === `/api/v1/knowledge-bases/${base.id}/search`) {
      searchPayload = request.postDataJSON()
      return json(route, {
        query: 'What changed in release 0.4?',
        matches: [{ documentId: document.id, chunkId: 'chunk-release-1', title: document.title,
          content: document.content, sourceUri: document.sourceUri, score: 0.8125, rerankScore: 0.9432, metadata: {} }],
        embeddingModel: base.embeddingModel, reranker: 'cross-encoder-v1', elapsedMs: 24, traceId: 'trace-search-1',
      })
    }
    if (request.method() === 'POST' && path === `/api/v1/knowledge-bases/${base.id}/rag`) {
      ragPayload = request.postDataJSON()
      return json(route, {
        answer: 'Release 0.4 adds portable JDBC vector storage and server-validated citations.',
        citations: [{ documentId: document.id, chunkId: 'chunk-release-1', title: document.title,
          sourceUri: document.sourceUri, excerpt: 'portable JDBC vector storage and grounded RAG citations' }],
        lowConfidence: false,
        retrieval: { query: 'What changed?', matches: [], embeddingModel: base.embeddingModel, elapsedMs: 12 },
        model: 'gpt-5-mini', provider: 'openai', usage: { totalTokens: 118 }, elapsedMs: 92, traceId: 'trace-rag-1',
      })
    }
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })

  await page.goto('/knowledge')
  await expect(page.getByRole('heading', { name: 'Knowledge & RAG' })).toBeVisible()
  await expect(page.getByText('No knowledge bases yet')).toBeVisible()
  await page.getByRole('button', { name: 'Create your first knowledge base' }).click()
  await page.getByLabel('Name').fill('Product Knowledge')
  await page.getByLabel('Description').fill('Release and architecture documentation.')
  await page.getByRole('button', { name: 'Create knowledge base', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Product Knowledge' })).toBeVisible()
  expect(createPayload).toMatchObject({ name: 'Product Knowledge', description: 'Release and architecture documentation.' })

  await page.getByRole('button', { name: 'Import source text' }).click()
  await page.getByLabel('Document title').fill('Release architecture')
  await page.getByLabel('Source content').fill(document.content)
  await page.getByLabel('Source URI (optional)').fill(document.sourceUri)
  await page.getByRole('button', { name: 'Import and index' }).click()
  await expect(page.getByText('Release architecture', { exact: true })).toBeVisible()
  await expect(page.getByText('Indexed', { exact: true }).last()).toBeVisible()
  expect(importPayload).toMatchObject({ title: document.title, content: document.content, sourceType: 'TEXT' })

  await page.getByLabel('Search query').fill('What changed in release 0.4?')
  await page.getByRole('button', { name: 'Run retrieval' }).click()
  await expect(page.getByText('0.8125')).toBeVisible()
  await expect(page.getByText('0.9432')).toBeVisible()
  expect(searchPayload).toMatchObject({ query: 'What changed in release 0.4?', topK: 5, rerank: true, rerankTopN: 5 })

  await page.getByLabel('Ask this knowledge base').fill('What changed?')
  await page.getByRole('button', { name: 'Generate answer' }).click()
  await expect(page.getByText('Release 0.4 adds portable JDBC vector storage and server-validated citations.')).toBeVisible()
  await expect(page.getByText('portable JDBC vector storage and grounded RAG citations', { exact: true })).toBeVisible()
  await expect(page.getByText('trace-rag-1')).toBeVisible()
  expect(ragPayload).toMatchObject({ message: 'What changed?', topK: 5, maxContextChunks: 5 })
})

test('renders an actionable provider-unconfigured state without hiding retrieval tools', async ({ page }) => {
  await mockExistingKnowledge(page, route => json(route, {
    timestamp: '2026-09-15T09:00:00Z', status: 503, error: 'provider_unconfigured',
    message: 'No Spring AI ChatModel is configured', requestId: 'req-provider-1', traceId: 'trace-provider-1',
  }, 503))
  await page.goto('/knowledge')
  await page.getByLabel('Ask this knowledge base').fill('Summarize the release.')
  await page.getByRole('button', { name: 'Generate answer' }).click()
  await expect(page.getByText('AI provider is not configured')).toBeVisible()
  await expect(page.getByText(/requires a Spring AI ChatModel and EmbeddingModel/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Run retrieval' })).toBeVisible()
})

test('shows partial indexing failure and the document-level failure reason', async ({ page }) => {
  const failedDocument = { ...document, id: 'doc-failed', title: 'Oversized handbook', indexStatus: 'FAILED',
    errorMessage: 'Embedding provider rejected the input after three attempts.' }
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/knowledge-bases') return json(route, pageOf([{ ...base, documentCount: 2 }]))
    if (request.method() === 'GET' && path.endsWith('/documents')) return json(route, pageOf([document, failedDocument]))
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })
  await page.goto('/knowledge')
  await expect(page.getByText('Partial indexing failure')).toBeVisible()
  await expect(page.getByText('Embedding provider rejected the input after three attempts.')).toBeVisible()
  await expect(page.getByText('Indexed', { exact: true }).last()).toBeVisible()
  await expect(page.getByText('Failed', { exact: true }).last()).toBeVisible()
})


test('does not render a late retrieval response after switching knowledge bases', async ({ page }) => {
  const otherBase = { ...base, id: 'kb-engineering', name: 'Engineering Knowledge', documentCount: 0, chunkCount: 0 }
  let releaseSearch!: () => void
  const searchBlocked = new Promise<void>(resolve => { releaseSearch = resolve })

  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/knowledge-bases') return json(route, pageOf([base, otherBase]))
    if (request.method() === 'GET' && path === `/api/v1/knowledge-bases/${base.id}/documents`) return json(route, pageOf([document]))
    if (request.method() === 'GET' && path === `/api/v1/knowledge-bases/${otherBase.id}/documents`) return json(route, pageOf([]))
    if (request.method() === 'POST' && path === `/api/v1/knowledge-bases/${base.id}/search`) {
      await searchBlocked
      return json(route, {
        query: 'delayed query',
        matches: [{ documentId: document.id, chunkId: 'stale-chunk', title: 'STALE RESULT SHOULD NOT APPEAR',
          content: document.content, score: 0.999, metadata: {} }],
        embeddingModel: base.embeddingModel, elapsedMs: 3000,
      })
    }
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })

  await page.goto('/knowledge')
  await page.getByLabel('Search query').fill('delayed query')
  const searchRequest = page.waitForRequest(request => request.method() === 'POST' && request.url().endsWith(`/${base.id}/search`))
  await page.getByRole('button', { name: 'Run retrieval' }).click()
  await searchRequest
  await page.getByRole('button', { name: /Engineering Knowledge/ }).click()
  await expect(page.getByRole('heading', { name: 'Engineering Knowledge' })).toBeVisible()
  releaseSearch()

  await expect(page.getByText('STALE RESULT SHOULD NOT APPEAR')).not.toBeVisible()
  await expect(page.getByText('Run a query to inspect evidence, scores, and trace metadata.')).toBeVisible()
})

test('keeps the knowledge workspace usable on a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockExistingKnowledge(page, route => json(route, { message: 'not used' }, 500))
  await page.goto('/knowledge')
  await expect(page.getByRole('heading', { name: 'Knowledge & RAG' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Product Knowledge' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Import document' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

async function mockExistingKnowledge(page: Page, rag: (route: Route) => Promise<void>) {
  await page.route('**/api/v1/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/knowledge-bases') return json(route, pageOf([base]))
    if (request.method() === 'GET' && path === `/api/v1/knowledge-bases/${base.id}/documents`) return json(route, pageOf([document]))
    if (request.method() === 'POST' && path === `/api/v1/knowledge-bases/${base.id}/rag`) return rag(route)
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
