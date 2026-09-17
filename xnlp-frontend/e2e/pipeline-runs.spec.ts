import { expect, test, type Download, type Page, type Route } from '@playwright/test'

type RunStatus = 'RUNNING' | 'CANCELLING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

type NodeRun = {
  nodeId: string
  capability: string
  status: string
  attempt: number
  maxAttempts: number
  input: Record<string, unknown>
  output: Record<string, unknown>
  errorCode: string | null
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
}

type PipelineRun = {
  id: string
  pipelineId: string
  pipelineVersion: number
  status: RunStatus
  input: Record<string, unknown>
  output: Record<string, unknown>
  cancelRequested: boolean
  nodes: NodeRun[]
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

type PipelineEvent = {
  id: number
  runId: string
  type: string
  status: string
  nodeId: string | null
  attempt: number | null
  detail: Record<string, unknown>
  occurredAt: string
}

const successfulRun: PipelineRun = {
  id: 'run-success-42',
  pipelineId: 'pipeline-support-triage',
  pipelineVersion: 7,
  status: 'COMPLETED',
  input: { text: 'Please summarise the support request.' },
  output: { summary: 'Customer requests a billing correction.' },
  cancelRequested: false,
  nodes: [
    {
      nodeId: 'normalise', capability: 'text.normalise', status: 'SUCCEEDED', attempt: 1, maxAttempts: 2,
      input: { text: 'Please summarise the support request.' }, output: { normalizedText: 'Please summarise the support request.' },
      errorCode: null, errorMessage: null, startedAt: '2026-09-17T01:00:00Z',
      completedAt: '2026-09-17T01:00:00.120Z', durationMs: 120,
    },
    {
      nodeId: 'summarise', capability: 'ai.summarise', status: 'SUCCEEDED', attempt: 1, maxAttempts: 3,
      input: { normalizedText: 'Please summarise the support request.' },
      output: { summary: 'Customer requests a billing correction.' }, errorCode: null, errorMessage: null,
      startedAt: '2026-09-17T01:00:00.121Z', completedAt: '2026-09-17T01:00:00.441Z', durationMs: 320,
    },
  ],
  errorCode: null,
  errorMessage: null,
  createdAt: '2026-09-17T00:59:59.900Z',
  startedAt: '2026-09-17T01:00:00Z',
  completedAt: '2026-09-17T01:00:00.441Z',
}

const failedRun: PipelineRun = {
  ...successfulRun,
  id: 'run-failed-17',
  status: 'FAILED',
  output: {},
  nodes: [
    successfulRun.nodes[0],
    {
      nodeId: 'summarise', capability: 'ai.summarise', status: 'FAILED', attempt: 2, maxAttempts: 2,
      input: { normalizedText: 'Please summarise the support request.' }, output: {},
      errorCode: 'provider_timeout', errorMessage: 'Provider timed out after 30000 ms.',
      startedAt: '2026-09-17T01:02:30Z', completedAt: '2026-09-17T01:03:00Z', durationMs: 30_000,
    },
  ],
  errorCode: 'pipeline_node_failed',
  errorMessage: 'Node summarise failed after 2 attempts.',
  completedAt: '2026-09-17T01:03:00Z',
}

const runningRun: PipelineRun = {
  ...successfulRun,
  id: 'run-active-9',
  status: 'RUNNING',
  output: {},
  nodes: [
    { ...successfulRun.nodes[0], status: 'RUNNING', output: {}, completedAt: null, durationMs: null },
    {
      ...successfulRun.nodes[1], status: 'QUEUED', attempt: 0, input: {}, output: {},
      startedAt: null, completedAt: null, durationMs: null,
    },
  ],
  startedAt: '2026-09-17T01:10:00Z',
  completedAt: null,
}

const successEvents: PipelineEvent[] = [
  event(1, successfulRun.id, 'RUN_STARTED', 'RUNNING', null, null, {}, '2026-09-17T01:00:00Z'),
  event(2, successfulRun.id, 'NODE_COMPLETED', 'SUCCEEDED', 'normalise', 1, { durationMs: 120 }, '2026-09-17T01:00:00.120Z'),
  event(3, successfulRun.id, 'NODE_COMPLETED', 'SUCCEEDED', 'summarise', 1, { durationMs: 320 }, '2026-09-17T01:00:00.441Z'),
  event(4, successfulRun.id, 'RUN_COMPLETED', 'COMPLETED', null, null, {}, '2026-09-17T01:00:00.441Z'),
]

const failedEvents: PipelineEvent[] = [
  event(1, failedRun.id, 'RUN_STARTED', 'RUNNING', null, null, {}, '2026-09-17T01:02:00Z'),
  event(2, failedRun.id, 'NODE_ATTEMPT_FAILED', 'FAILED', 'summarise', 1, { errorCode: 'provider_timeout' }, '2026-09-17T01:02:30Z'),
  event(3, failedRun.id, 'NODE_ATTEMPT_FAILED', 'FAILED', 'summarise', 2, { errorCode: 'provider_timeout' }, '2026-09-17T01:03:00Z'),
  event(4, failedRun.id, 'RUN_FAILED', 'FAILED', null, null, { errorCode: 'pipeline_node_failed' }, '2026-09-17T01:03:00Z'),
]

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('xnlp.language', 'en'))
  await page.route('**/health', route => json(route, { status: 'UP' }))
})

test('shows a successful run DAG and events, then downloads its complete trace', async ({ page }) => {
  const trace = traceFor(successfulRun, successEvents)
  await mockPipelineRuns(page, [successfulRun], { [successfulRun.id]: successEvents }, { [successfulRun.id]: trace })

  await openRun(page, successfulRun.id)

  await expect(page.getByText('Completed', { exact: true }).last()).toBeVisible()
  const dag = page.getByLabel('DAG node status')
  const normaliseNode = dag.getByRole('article').filter({ has: page.getByRole('heading', { name: 'normalise' }) })
  const summariseNode = dag.getByRole('article').filter({ has: page.getByRole('heading', { name: 'summarise' }) })
  await expect(normaliseNode).toContainText('Succeeded')
  await expect(summariseNode).toContainText('Succeeded')
  await expect(dag.getByText('text.normalise', { exact: true })).toBeVisible()
  await expect(dag.getByText('ai.summarise', { exact: true })).toBeVisible()

  const eventList = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Event timeline' }) }).locator('ol')
  await expect(eventList.getByRole('listitem')).toHaveCount(successEvents.length)
  await expect(eventList.getByText('RUN_STARTED', { exact: true })).toHaveCount(1)
  await expect(eventList.getByText('NODE_COMPLETED', { exact: true })).toHaveCount(2)
  await expect(eventList.getByText('RUN_COMPLETED', { exact: true })).toHaveCount(1)

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download trace' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe(`pipeline-trace-${successfulRun.id}.json`)
  await expectDownloadedTrace(download, trace)
})

test('shows the failed node error and every persisted attempt', async ({ page }) => {
  const trace = {
    ...traceFor(failedRun, failedEvents),
    nodes: [
      {
        nodeId: 'normalise', capability: 'text.normalise', status: 'SUCCEEDED', errorCode: null, errorMessage: null,
        output: successfulRun.nodes[0].output,
        attempts: [attempt(1, 'SUCCEEDED', successfulRun.nodes[0].input, successfulRun.nodes[0].output, null, null, 120)],
      },
      {
        nodeId: 'summarise', capability: 'ai.summarise', status: 'FAILED', output: {},
        errorCode: 'provider_timeout', errorMessage: 'Provider timed out after 30000 ms.',
        attempts: [
          attempt(1, 'FAILED', failedRun.nodes[1].input, {}, 'provider_timeout', 'Provider timed out after 30000 ms.', 30_000),
          attempt(2, 'FAILED', failedRun.nodes[1].input, {}, 'provider_timeout', 'Provider timed out after 30000 ms.', 30_000),
        ],
      },
    ],
  }
  await mockPipelineRuns(page, [failedRun], { [failedRun.id]: failedEvents }, { [failedRun.id]: trace })

  await openRun(page, failedRun.id)

  await expect(page.getByText('Failed', { exact: true }).last()).toBeVisible()
  const failedNode = page.getByLabel('DAG node status').getByRole('article').filter({ has: page.getByRole('heading', { name: 'summarise' }) })
  await expect(failedNode).toContainText('provider_timeout')
  await expect(failedNode).toContainText('Provider timed out after 30000 ms.')
  await expect(failedNode).toContainText(/attempt 2\/2/i)

  await failedNode.getByText(/Attempts \(2\)/).click()
  const attempts = failedNode.getByRole('list')
  await expect(attempts.getByRole('listitem')).toHaveCount(2)
  await expect(attempts.getByRole('listitem').nth(0)).toContainText('provider_timeout')
  await expect(attempts.getByRole('listitem').nth(1)).toContainText('provider_timeout')
})

test('cancels an active run and renders the cancellation state', async ({ page }) => {
  let cancelRequestCount = 0
  const runStates = [runningRun]
  const cancellingRun: PipelineRun = { ...runningRun, status: 'CANCELLING', cancelRequested: true }
  await mockPipelineRuns(page, runStates, { [runningRun.id]: [
    event(1, runningRun.id, 'RUN_STARTED', 'RUNNING', null, null, {}, '2026-09-17T01:10:00Z'),
  ] }, {}, async route => {
    cancelRequestCount += 1
    runStates[0] = cancellingRun
    await json(route, cancellingRun)
  })

  await openRun(page, runningRun.id)
  const cancel = page.getByRole('button', { name: 'Cancel run' })
  await expect(cancel).toBeEnabled()
  await cancel.click()

  await expect.poll(() => cancelRequestCount).toBe(1)
  await expect(page.getByRole('heading', { name: runningRun.id }).locator('..').getByText('Cancelling', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Cancelling…' })).toBeDisabled()
})

test('reconnects the event stream with Last-Event-ID and does not duplicate replayed events', async ({ page }) => {
  let connectionCount = 0
  let reconnectLastEventId: string | null = null
  const firstBatch = [
    event(1, runningRun.id, 'RUN_STARTED', 'RUNNING', null, null, {}, '2026-09-17T01:10:00Z'),
    event(2, runningRun.id, 'NODE_STARTED', 'RUNNING', 'normalise', 1, {}, '2026-09-17T01:10:00.010Z'),
  ]
  const replayAndCompletion = [
    firstBatch[1],
    event(3, runningRun.id, 'NODE_COMPLETED', 'SUCCEEDED', 'normalise', 1, {}, '2026-09-17T01:10:00.120Z'),
    event(4, runningRun.id, 'RUN_COMPLETED', 'COMPLETED', null, null, {}, '2026-09-17T01:10:00.441Z'),
  ]

  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/pipeline-runs') return json(route, pageOf([runningRun]))
    if (request.method() === 'GET' && path === `/api/v1/pipeline-runs/${runningRun.id}`) return json(route, runningRun)
    if (request.method() === 'GET' && path === `/api/v1/pipeline-runs/${runningRun.id}/events`) {
      connectionCount += 1
      if (connectionCount === 1) return sse(route, firstBatch, 20)
      reconnectLastEventId = await request.headerValue('last-event-id')
      return sse(route, replayAndCompletion, 20)
    }
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })

  await openRun(page, runningRun.id)

  await expect.poll(() => connectionCount).toBeGreaterThanOrEqual(2)
  expect(reconnectLastEventId).toBe('2')
  const list = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Event timeline' }) }).locator('ol')
  await expect(list.getByRole('listitem')).toHaveCount(4)
  await expect(list.getByText('NODE_STARTED', { exact: true })).toHaveCount(1)
})

test('keeps run inspection usable without horizontal overflow on a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockPipelineRuns(page, [successfulRun], { [successfulRun.id]: successEvents }, {
    [successfulRun.id]: traceFor(successfulRun, successEvents),
  })

  await openRun(page, successfulRun.id)

  await expect(page.getByRole('heading', { name: 'Pipeline runs' })).toBeVisible()
  await expect(page.getByLabel('DAG node status')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Download trace' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Download trace' })).toBeEnabled()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(1)
})

async function openRun(page: Page, runId: string) {
  await page.goto('/pipeline-runs')
  await expect(page.getByRole('heading', { name: 'Pipeline runs' })).toBeVisible()
  const listItem = page.getByRole('button').filter({ hasText: runId })
  await expect(listItem).toContainText(runId)
  await listItem.click()
  await expect(page.getByRole('heading', { name: runId })).toBeVisible()
}

async function mockPipelineRuns(
  page: Page,
  runs: PipelineRun[],
  events: Record<string, PipelineEvent[]>,
  traces: Record<string, unknown>,
  cancel?: (route: Route) => Promise<void>,
) {
  await page.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/v1/pipeline-runs') return json(route, pageOf(runs))

    const match = path.match(/^\/api\/v1\/pipeline-runs\/([^/]+)(?:\/(events|trace|cancel))?$/)
    if (!match) return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
    const runId = decodeURIComponent(match[1])
    const operation = match[2]
    const run = runs.find(item => item.id === runId)
    if (!run) return json(route, { error: 'pipeline_run_not_found', message: 'Pipeline run was not found.' }, 404)
    if (request.method() === 'GET' && !operation) return json(route, run)
    if (request.method() === 'GET' && operation === 'events') return sse(route, events[runId] ?? [])
    if (request.method() === 'GET' && operation === 'trace') {
      const body = traces[runId]
      if (!body) return json(route, { error: 'trace_not_ready', message: 'Trace is not ready.' }, 409)
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Content-Disposition': `attachment; filename="pipeline-trace-${runId}.json"` },
        body: JSON.stringify(body),
      })
    }
    if (request.method() === 'POST' && operation === 'cancel' && cancel) return cancel(route)
    return json(route, { message: `Unexpected test request: ${request.method()} ${path}` }, 500)
  })
}

function pageOf(items: PipelineRun[]) {
  return { items, page: 0, size: 50, total: items.length, totalPages: items.length ? 1 : 0, hasNext: false }
}

function event(
  id: number,
  runId: string,
  type: string,
  status: string,
  nodeId: string | null,
  attemptNumber: number | null,
  detail: Record<string, unknown>,
  occurredAt: string,
): PipelineEvent {
  return { id, runId, type, status, nodeId, attempt: attemptNumber, detail, occurredAt }
}

function attempt(
  attemptNumber: number,
  status: string,
  input: Record<string, unknown>,
  output: Record<string, unknown>,
  errorCode: string | null,
  errorMessage: string | null,
  durationMs: number,
) {
  return {
    attempt: attemptNumber, status, input, output, errorCode, errorMessage,
    startedAt: '2026-09-17T01:02:00Z', completedAt: '2026-09-17T01:02:30Z', durationMs,
  }
}

function traceFor(run: PipelineRun, events: PipelineEvent[]) {
  return {
    traceId: `trace-${run.id}`,
    runId: run.id,
    pipelineId: run.pipelineId,
    pipelineVersion: run.pipelineVersion,
    status: run.status,
    startedAt: run.startedAt,
    completedAt: run.completedAt,
    durationMs: run.completedAt ? 441 : null,
    nodes: run.nodes.map(node => ({
      nodeId: node.nodeId,
      capability: node.capability,
      status: node.status,
      attempts: [attempt(node.attempt, node.status, node.input, node.output, node.errorCode, node.errorMessage, node.durationMs ?? 0)],
      output: node.output,
      errorCode: node.errorCode,
      errorMessage: node.errorMessage,
    })),
    events,
  }
}

async function sse(route: Route, events: PipelineEvent[], retry?: number) {
  const body = `${retry == null ? '' : `retry: ${retry}\n`}${events.map(item =>
    `id: ${item.id}\ndata: ${JSON.stringify(item)}\n\n`).join('')}`
  await route.fulfill({ status: 200, contentType: 'text/event-stream', headers: { 'Cache-Control': 'no-cache' }, body })
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function expectDownloadedTrace(download: Download, expected: unknown) {
  const stream = await download.createReadStream()
  let contents = ''
  stream.setEncoding('utf8')
  for await (const chunk of stream) contents += chunk
  expect(JSON.parse(contents)).toEqual(expected)
}
