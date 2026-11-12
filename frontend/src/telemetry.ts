/**
 * Browser tracing (R-FE-07): OTel Web SDK → OTLP/HTTP → collector :4318.
 *
 * One USER ACTION = one trace, across browser and backend:
 *  1. A click on an element marked `data-action="…"` (or opening the app)
 *     starts a root "action span" named after the action.
 *  2. Every API request issued while that action is open runs inside the
 *     action's context (see tracedFetch), so the fetch instrumentation's
 *     span is a child of the action span.
 *  3. The fetch instrumentation adds a W3C `traceparent` header to requests
 *     to the API origin; the backend's OTel agent continues the trace, so the
 *     HTTP server span and its DynamoDB calls join the same trace.
 *  4. The action span ends once its requests settle (or after a short idle
 *     timeout if the action triggered no request, e.g. a cache hit).
 *
 * Why not Zone.js + the user-interaction instrumentation? React 19 schedules
 * work through its own scheduler and native async/await, which Zone.js
 * can't follow, so clicks and their fetches ended up in separate traces.
 * Explicit context passing is simpler and deterministic.
 *
 * Telemetry is best-effort: with no collector URL configured it stays off,
 * and export failures never affect the app.
 */
import { context, propagation, ROOT_CONTEXT, trace, type Context, type Span } from '@opentelemetry/api';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  BatchSpanProcessor,
  StackContextManager,
  TracerProvider,
  type SpanExporter,
  type SpanProcessor,
} from '@opentelemetry/sdk-trace';
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions';

export const SERVICE_NAME = 'homehub-web';
export const ATTR_UI_ACTION = 'homehub.ui.action';
/** End an action that started no request after this long. */
export const ACTION_IDLE_MS = 1000;
/** After the last request settles, wait this long for follow-up requests. */
export const ACTION_SETTLE_MS = 100;

export interface TelemetryEnv {
  VITE_OTEL_COLLECTOR_URL?: string;
  VITE_API_BASE_URL?: string;
  VITE_APP_VERSION?: string;
  MODE?: string;
}

export interface TelemetrySettings {
  enabled: boolean;
  tracesUrl: string;
  serviceVersion: string;
  environment: string;
  /** Requests to these URLs carry `traceparent` (CORS: only our API). */
  propagateTo: RegExp[];
  /** Never trace these (the exporter's own requests to the collector). */
  ignore: RegExp[];
}

const escapeRegExp = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const stripSlash = (s: string) => s.replace(/\/+$/, '');

export function telemetrySettings(env: TelemetryEnv): TelemetrySettings {
  const collector = env.VITE_OTEL_COLLECTOR_URL ? stripSlash(env.VITE_OTEL_COLLECTOR_URL) : '';
  const api = env.VITE_API_BASE_URL ? stripSlash(env.VITE_API_BASE_URL) : '';
  return {
    enabled: collector !== '',
    tracesUrl: `${collector}/v1/traces`,
    serviceVersion: env.VITE_APP_VERSION || 'dev',
    environment: env.MODE === 'production' ? 'production' : 'local',
    propagateTo: api ? [new RegExp(`^${escapeRegExp(api)}(/|$)`)] : [],
    ignore: collector ? [new RegExp(`^${escapeRegExp(collector)}(/|$)`)] : [],
  };
}

/**
 * Presigned S3 URLs are bearer tokens — they must never reach telemetry
 * (CLAUDE.md). Drops every X-Amz-* query parameter; leaves other URLs as-is.
 */
export function redactUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return raw;
  }
  const keys = [...url.searchParams.keys()].filter((k) => k.toLowerCase().startsWith('x-amz-'));
  if (keys.length === 0) return raw;
  keys.forEach((k) => url.searchParams.delete(k));
  url.searchParams.set('redacted', 'presigned');
  return url.toString();
}

/** Nearest `data-action` on the element or its ancestors. */
export function actionFor(element: Element | null): string | undefined {
  return element?.closest<HTMLElement>('[data-action]')?.dataset.action;
}

/** Fetch-span hook: overwrite url.full if the request URL is presigned. */
export function redactFetchSpan(span: Pick<Span, 'setAttribute'>, request: Request | RequestInit) {
  const url = request instanceof Request ? request.url : undefined;
  if (url && redactUrl(url) !== url) span.setAttribute('url.full', redactUrl(url));
}

// ---- user actions ---------------------------------------------------------

interface OpenAction {
  span: Span;
  ctx: Context;
  pending: number;
  timer?: ReturnType<typeof setTimeout>;
}

let current: OpenAction | undefined;

function endAction(action: OpenAction) {
  clearTimeout(action.timer);
  action.span.end();
  if (current === action) current = undefined;
}

function scheduleEnd(action: OpenAction, ms: number) {
  clearTimeout(action.timer);
  action.timer = setTimeout(() => endAction(action), ms);
}

/**
 * Starts a root span for a user action. A new action ends any previous one.
 * Span names are low-cardinality verbs (`search-listings`), never ids.
 */
export function startUserAction(name: string): Span {
  if (current) endAction(current);
  const span = trace.getTracer(SERVICE_NAME).startSpan(
    name,
    { attributes: { [ATTR_UI_ACTION]: name, 'url.path': window.location.pathname } },
    ROOT_CONTEXT,
  );
  const action: OpenAction = { span, ctx: trace.setSpan(ROOT_CONTEXT, span), pending: 0 };
  current = action;
  scheduleEnd(action, ACTION_IDLE_MS);
  return span;
}

/**
 * The API client's fetch. Runs the request inside the open user action (if
 * any) so it — and the backend work behind it — joins the action's trace.
 * Resolving `fetch` per call also lets wrappers installed later (MSW in
 * tests, the fetch instrumentation) see the request.
 */
export function tracedFetch(request: Request, impl: (r: Request) => Promise<Response> = (r) => globalThis.fetch(r)) {
  const action = current;
  if (!action) return impl(request);
  clearTimeout(action.timer);
  action.pending++;
  const settle = () => {
    action.pending--;
    if (action.pending === 0 && current === action) scheduleEnd(action, ACTION_SETTLE_MS);
  };
  const result = context.with(action.ctx, () => impl(request));
  result.then(settle, settle);
  return result;
}

function onClick(event: MouseEvent) {
  const name = actionFor(event.target instanceof Element ? event.target : null);
  if (name) startUserAction(name);
}

// ---- setup ----------------------------------------------------------------

/**
 * Installs the global tracer provider, context manager, propagator, fetch
 * instrumentation and the click listener. `processor` is injectable for tests.
 * Returns a teardown function (used by tests), or undefined when disabled.
 */
export function initTelemetry(
  settings: TelemetrySettings,
  processor?: SpanProcessor,
  exporter?: SpanExporter,
): (() => void) | undefined {
  if (!settings.enabled) return undefined;

  const provider = new TracerProvider({
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: SERVICE_NAME,
      [ATTR_SERVICE_VERSION]: settings.serviceVersion,
      'deployment.environment': settings.environment,
    }),
    spanProcessors: [
      processor ??
        // sdk-trace 3.x: options object (2.x took the exporter positionally).
        new BatchSpanProcessor({ exporter: exporter ?? new OTLPTraceExporter({ url: settings.tracesUrl }) }),
    ],
  });

  // sdk-trace 3.x has no provider.register(): set each global explicitly.
  // StackContextManager: synchronous context.with() is all tracedFetch needs.
  trace.setGlobalTracerProvider(provider);
  context.setGlobalContextManager(new StackContextManager().enable());
  propagation.setGlobalPropagator(new W3CTraceContextPropagator());

  const unregister = registerInstrumentations({
    tracerProvider: provider,
    instrumentations: [
      new FetchInstrumentation({
        propagateTraceHeaderCorsUrls: settings.propagateTo,
        ignoreUrls: settings.ignore,
        clearTimingResources: true,
        applyCustomAttributesOnSpan: redactFetchSpan,
      }),
    ],
  });

  // Capture phase: runs before React's handlers, so the action is open by
  // the time the click's state change triggers a request.
  document.addEventListener('click', onClick, true);
  startUserAction('open-app');

  return () => {
    document.removeEventListener('click', onClick, true);
    if (current) endAction(current);
    unregister();
    trace.disable();
    context.disable();
    propagation.disable();
  };
}
