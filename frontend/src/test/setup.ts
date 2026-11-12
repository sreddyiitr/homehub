import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

// MSW intercepts fetch at the network layer, so tests exercise the real
// API client (openapi-fetch) instead of mocking our own modules.
// 'error' = any request without a handler fails the test (MSW 3 renamed
// this option from onUnhandledRequest).
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());
