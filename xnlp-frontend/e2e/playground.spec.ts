import { expect, test, type Page, type Route } from '@playwright/test'

const model = {
  name: 'ollama-default',
  type: 'CHAT',
  provider: 'ollama',
  modelName: 'qwen3:8b',
  protocol: 'OLLAMA_CHAT',
  status: 'loaded',
}

const diagnostic = {
  name: 'ollama-default',
  type: 'CHAT',
  protocol: 'OLLAMA_CHAT',
  provider: 'ollama',
  model: 'qwen3:8b',
  endpoint: 'http://localhost:11434',
  configured: true,
  reachable: true,
  usable: true,
  status: 'usable',
  elapsedMillis: 18,
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('xnlp.language', 'en'))
  await page.route('**/health', route => json(route, { status: 'UP' }))
})

test('runs a prediction and renders response metadata', async ({ page }) => {
  let requestBody: Record<string, unknown> | undefined
  await mockPlayground(page, async route => {
    requestBody = route.request().postDataJSON()
    await new Promise(resolve => setTimeout(resolve, 250))
    await json(route, {
      model: 'ollama-default',
      text: 'X-NLP turns provider calls into observable engineering workflows.',
      elapsedSeconds: 0.123,
    })
  })

  await page.goto('/playground')

  await expect(page.getByRole('heading', { name: 'Model Playground' })).toBeVisible()
  await expect(page.getByLabel('Model')).toHaveValue('ollama-default')
  await expect(page.getByText('Usable', { exact: true })).toBeVisible()

  await page.getByLabel('Input text').fill('Explain X-NLP in one sentence.')
  await page.getByLabel('Max output length').fill('512')
  await page.getByRole('button', { name: 'Run prediction' }).click()

  await expect(page.getByRole('button', { name: 'Requesting…' })).toBeDisabled()
  await expect(page.getByText('X-NLP turns provider calls into observable engineering workflows.', { exact: true })).toBeVisible()
  await expect(page.getByText('0.123 s')).toBeVisible()
  expect(requestBody).toMatchObject({
    text: 'Explain X-NLP in one sentence.',
    max_length: 512,
    parameters: { temperature: 0.2 },
  })
})

test('shows the stable error contract when prediction fails', async ({ page }) => {
  await mockPlayground(page, route => json(route, {
    timestamp: '2026-09-15T09:00:00Z',
    status: 503,
    error: 'PROVIDER_UNAVAILABLE',
    message: 'The configured provider is not reachable.',
    requestId: 'req-e2e-123',
    traceId: 'trace-e2e-456',
  }, 503))

  await page.goto('/playground')
  await page.getByRole('button', { name: 'Run prediction' }).click()

  const alert = page.getByText(/The configured provider is not reachable\./)
  await expect(alert).toBeVisible()
  await expect(alert).toContainText('error=PROVIDER_UNAVAILABLE')
  await expect(alert).toContainText('requestId=req-e2e-123')
  await expect(alert).toContainText('traceId=trace-e2e-456')
  await expect(page.getByText('Waiting for a prediction')).toBeVisible()
})

test('renders an actionable empty state when no chat model exists', async ({ page }) => {
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/models') return json(route, [])
    if (path === '/api/v1/models/diagnostics') {
      return json(route, { checkedAt: '2026-09-15T09:00:00Z', activeProbe: false, diagnostics: [] })
    }
    return json(route, { message: `Unexpected test request: ${path}` }, 500)
  })

  await page.goto('/playground')

  await expect(page.getByLabel('Model')).toBeDisabled()
  await expect(page.getByRole('option', { name: 'No chat model configured' })).toBeAttached()
  await expect(page.getByRole('button', { name: 'Run prediction' })).toBeDisabled()
  await expect(page.getByText('Select a model to see its profile and provider diagnostics.')).toBeVisible()
})

async function mockPlayground(page: Page, predict: (route: Route) => Promise<void>) {
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/models') return json(route, [model])
    if (request.method() === 'GET' && path === '/api/v1/models/diagnostics') {
      return json(route, { checkedAt: '2026-09-15T09:00:00Z', activeProbe: false, diagnostics: [diagnostic] })
    }
    if (request.method() === 'POST' && path === '/api/v1/models/ollama-default/predict') return predict(route)
    if (request.method() === 'POST' && path === '/api/v1/models/diagnostics/probe') {
      return json(route, { checkedAt: '2026-09-15T09:00:01Z', activeProbe: true, diagnostics: [diagnostic] })
    }
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}
