import { ApiError, unwrap } from './client';

describe('unwrap', () => {
  const res = (status: number) => new Response(null, { status });

  it('returns data when present', () => {
    expect(unwrap({ data: { a: 1 }, response: res(200) })).toEqual({ a: 1 });
  });

  it('throws ApiError carrying the problem and HTTP status', () => {
    expect(() => unwrap({ error: { title: 'Nope' }, response: res(409) })).toThrow(
      expect.objectContaining({ problem: { title: 'Nope', status: 409 }, message: 'Nope' }),
    );
  });

  it('falls back to the status when the body is empty', () => {
    const err = (() => {
      try {
        unwrap({ response: res(502) });
      } catch (e) {
        return e as ApiError;
      }
    })();
    expect(err).toBeInstanceOf(ApiError);
    expect(err?.message).toBe('HTTP 502');
  });
});
