import { propagation, context as otelContext, trace } from '@opentelemetry/api';
import { InMemorySpanExporter, SimpleSpanProcessor } from '@opentelemetry/sdk-trace';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ACTION_IDLE_MS,
  ACTION_SETTLE_MS,
  ATTR_UI_ACTION,
  actionFor,
  initTelemetry,
  redactFetchSpan,
  redactUrl,
  SERVICE_NAME,
  startUserAction,
  telemetrySettings,
  tracedFetch,
} from './telemetry';

const ENV = {
  VITE_OTEL_COLLECTOR_URL: 'http://collector.test:4318/',
  VITE_API_BASE_URL: 'http://api.test',
  VITE_APP_VERSION: 'abc1234',
  MODE: 'development',
};

describe('telemetrySettings', () => {
  it('is disabled without a collector URL (best-effort, e.g. unit tests)', () => {
    const s = telemetrySettings({});
    expect(s.enabled).toBe(false);
    expect(s.propagateTo).toEqual([]);
    expect(s.ignore).toEqual([]);
    expect(s.serviceVersion).toBe('dev');
  });

  it('derives exporter URL, propagation and ignore lists from env', () => {
    const s = telemetrySettings(ENV);
    expect(s.enabled).toBe(true);
    expect(s.tracesUrl).toBe('http://collector.test:4318/v1/traces');
    expect(s.serviceVersion).toBe('abc1234');
    expect(s.environment).toBe('local');
    // traceparent only to OUR API origin — not look-alike hosts.
    expect(s.propagateTo[0]!.test('http://api.test/listings/search')).toBe(true);
    expect(s.propagateTo[0]!.test('http://api.test.evil.com/x')).toBe(false);
    expect(s.ignore[0]!.test('http://collector.test:4318/v1/traces')).toBe(true);
  });

  it('labels production builds', () => {
    expect(telemetrySettings({ ...ENV, MODE: 'production' }).environment).toBe('production');
  });
});

describe('redactUrl (presigned URLs are bearer tokens)', () => {
  it('removes X-Amz-* parameters', () => {
    const out = redactUrl('https://b.s3.test/k.jpg?X-Amz-Signature=SECRET_SIG&x-amz-credential=SECRET_CRED&size=2');
    expect(out).not.toContain('SECRET_SIG');
    expect(out).not.toContain('SECRET_CRED');
    expect(out).toContain('size=2');
    expect(out).toContain('redacted=presigned');
  });

  it('leaves ordinary and unparsable URLs untouched', () => {
    expect(redactUrl('http://api.test/listings?city=Seattle')).toBe('http://api.test/listings?city=Seattle');
    expect(redactUrl('not a url')).toBe('not a url');
  });
});

describe('redactFetchSpan', () => {
  it('overwrites url.full only for presigned Request URLs', () => {
    const span = { setAttribute: vi.fn() };
    redactFetchSpan(span, new Request('https://b.s3.test/k?X-Amz-Signature=SECRET'));
    expect(span.setAttribute).toHaveBeenCalledWith('url.full', 'https://b.s3.test/k?redacted=presigned');

    span.setAttribute.mockClear();
    redactFetchSpan(span, new Request('http://api.test/listings'));
    redactFetchSpan(span, { method: 'GET' }); // RequestInit: no URL to inspect
    expect(span.setAttribute).not.toHaveBeenCalled();
  });
});

describe('initTelemetry defaults', () => {
  it('builds the OTLP exporter pipeline when nothing is injected', () => {
    const teardown = initTelemetry(telemetrySettings(ENV));
    expect(teardown).toBeTypeOf('function');
    teardown!();
  });

  it('accepts an injected exporter (wrapped in a batch processor)', () => {
    const teardown = initTelemetry(telemetrySettings(ENV), undefined, new InMemorySpanExporter());
    expect(teardown).toBeTypeOf('function');
    teardown!();
  });
});

describe('actionFor', () => {
  it('finds the nearest data-action', () => {
    document.body.innerHTML = '<a data-action="open-listing"><span id="inner">x</span></a><p id="plain">y</p>';
    expect(actionFor(document.getElementById('inner'))).toBe('open-listing');
    expect(actionFor(document.getElementById('plain'))).toBeUndefined();
    expect(actionFor(null)).toBeUndefined();
  });
});

describe('initTelemetry + user actions (R-FE-07)', () => {
  let exporter: InMemorySpanExporter;
  let teardown: (() => void) | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    exporter = new InMemorySpanExporter();
    teardown = initTelemetry(telemetrySettings(ENV), new SimpleSpanProcessor({ exporter }));
  });

  afterEach(() => {
    teardown?.();
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  const finished = () => exporter.getFinishedSpans();

  it('returns undefined (no-op) when disabled', () => {
    expect(initTelemetry(telemetrySettings({}))).toBeUndefined();
  });

  it('opening the app is itself an action; it ends after the idle timeout', () => {
    expect(finished()).toHaveLength(0);
    vi.advanceTimersByTime(ACTION_IDLE_MS);

    const [span] = finished();
    expect(span!.name).toBe('open-app');
    expect(span!.attributes[ATTR_UI_ACTION]).toBe('open-app');
    expect(span!.resource.attributes['service.name']).toBe(SERVICE_NAME);
    expect(span!.resource.attributes['service.version']).toBe('abc1234');
    expect(span!.parentSpanContext).toBeUndefined(); // root of its own trace
  });

  it('a click on [data-action] starts a new action and ends the previous one', () => {
    document.body.innerHTML = '<button data-action="search-listings"><b id="label">Search</b></button>';
    document.getElementById('label')!.click();

    expect(finished().map((s) => s.name)).toEqual(['open-app']);
    vi.advanceTimersByTime(ACTION_IDLE_MS);
    expect(finished().map((s) => s.name)).toEqual(['open-app', 'search-listings']);
  });

  it('clicks on unnamed elements start nothing', () => {
    vi.advanceTimersByTime(ACTION_IDLE_MS); // end open-app
    document.body.innerHTML = '<p id="plain">hi</p>';
    document.getElementById('plain')!.click();
    vi.advanceTimersByTime(ACTION_IDLE_MS);
    expect(finished().map((s) => s.name)).toEqual(['open-app']);
  });

  it('requests run inside the action context and carry its trace id; the action ends once they settle', async () => {
    const action = startUserAction('search-listings');
    let activeTraceId: string | undefined;
    let traceparent: string | undefined;
    let resolve!: (r: Response) => void;

    const pending = tracedFetch(new Request('http://api.test/listings/search'), () => {
      activeTraceId = trace.getActiveSpan()?.spanContext().traceId;
      const carrier: Record<string, string> = {};
      propagation.inject(otelContext.active(), carrier);
      traceparent = carrier.traceparent;
      return new Promise<Response>((r) => (resolve = r));
    });

    expect(activeTraceId).toBe(action.spanContext().traceId);
    expect(traceparent).toContain(action.spanContext().traceId);

    vi.advanceTimersByTime(ACTION_IDLE_MS * 5); // in flight: the action must stay open
    expect(finished().map((s) => s.name)).not.toContain('search-listings');

    resolve(new Response('{}'));
    await pending;
    await vi.advanceTimersByTimeAsync(ACTION_SETTLE_MS);
    expect(finished().map((s) => s.name)).toContain('search-listings');
  });

  it('concurrent requests keep the action open until the last one settles', async () => {
    startUserAction('open-listing');
    let resolveA!: (r: Response) => void;
    let resolveB!: (r: Response) => void;
    const a = tracedFetch(new Request('http://api.test/a'), () => new Promise((r) => (resolveA = r)));
    const b = tracedFetch(new Request('http://api.test/b'), () => new Promise((r) => (resolveB = r)));

    resolveA(new Response('a'));
    await a;
    await vi.advanceTimersByTimeAsync(ACTION_SETTLE_MS * 3);
    expect(finished().map((s) => s.name)).not.toContain('open-listing');

    resolveB(new Response('b'));
    await b;
    await vi.advanceTimersByTimeAsync(ACTION_SETTLE_MS);
    expect(finished().map((s) => s.name)).toContain('open-listing');
  });

  it('a request finishing after its action was replaced does not end the new action', async () => {
    startUserAction('search-listings');
    let resolve!: (r: Response) => void;
    const p = tracedFetch(new Request('http://api.test/a'), () => new Promise((r) => (resolve = r)));
    startUserAction('open-listing'); // ends search-listings

    resolve(new Response('late'));
    await p;
    await vi.advanceTimersByTimeAsync(ACTION_SETTLE_MS);
    expect(finished().map((s) => s.name)).toEqual(['open-app', 'search-listings']);
    vi.advanceTimersByTime(ACTION_IDLE_MS);
    expect(finished().map((s) => s.name)).toContain('open-listing');
  });

  it('ignores clicks whose target is not an element', () => {
    vi.advanceTimersByTime(ACTION_IDLE_MS);
    document.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    vi.advanceTimersByTime(ACTION_IDLE_MS);
    expect(finished().map((s) => s.name)).toEqual(['open-app']);
  });

  it('a failed request still settles the action', async () => {
    startUserAction('load-more-listings');
    await expect(tracedFetch(new Request('http://api.test/x'), () => Promise.reject(new Error('down')))).rejects.toThrow(
      'down',
    );
    await vi.advanceTimersByTimeAsync(ACTION_SETTLE_MS);
    expect(finished().map((s) => s.name)).toContain('load-more-listings');
  });

  it('with no open action, requests pass straight through', async () => {
    vi.advanceTimersByTime(ACTION_IDLE_MS); // end open-app
    const impl = vi.fn(async () => new Response('ok'));
    await tracedFetch(new Request('http://api.test/y'), impl);
    expect(impl).toHaveBeenCalledOnce();
    expect(trace.getActiveSpan()).toBeUndefined();
  });

  it('teardown ends the open action and removes the click listener', () => {
    teardown!();
    teardown = undefined;
    expect(finished().map((s) => s.name)).toEqual(['open-app']);

    document.body.innerHTML = '<button data-action="search-listings" id="b">Search</button>';
    document.getElementById('b')!.click();
    vi.advanceTimersByTime(ACTION_IDLE_MS);
    expect(finished()).toHaveLength(1);
  });
});
