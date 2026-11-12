import createClient from 'openapi-fetch';
import { tracedFetch } from '../telemetry';
import type { components, paths } from './schema.gen';

/**
 * Typed HTTP client. `paths` is generated from the backend's OpenAPI spec
 * (npm run gen:api), so a renamed field or endpoint in the backend becomes a
 * TypeScript compile error here — the frontend never imports backend code.
 */
export const api = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
  // Runs each request inside the current user action's trace context, and
  // resolves globalThis.fetch per call so later wrappers (MSW in tests, the
  // OTel fetch instrumentation) see it. See telemetry.ts.
  fetch: (request) => tracedFetch(request),
});

export type Listing = components['schemas']['ListingResponse'];
export type ListingSearchResponse = components['schemas']['ListingSearchResponse'];

/** RFC 9457 problem+json, as returned by the API on errors. */
export interface Problem {
  status: number;
  title?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
  }
}

/** Unwraps an openapi-fetch result: returns data or throws ApiError. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data !== undefined) {
    return result.data;
  }
  const body = (result.error ?? {}) as Partial<Problem>;
  throw new ApiError({ ...body, status: result.response.status });
}
