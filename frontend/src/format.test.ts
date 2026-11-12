import { formatPrice, parseOptionalInt, statusLabel } from './format';

describe('format', () => {
  it('formats whole-dollar prices', () => {
    expect(formatPrice(1200000)).toBe('$1,200,000');
  });

  it('labels every status', () => {
    expect(['DRAFT', 'ACTIVE', 'UNDER_OFFER', 'SOLD'].map((s) => statusLabel(s as never))).toEqual([
      'Draft',
      'For sale',
      'Under offer',
      'Sold',
    ]);
  });

  it.each([
    ['', undefined],
    ['  ', undefined],
    ['42', 42],
    [' 7 ', 7],
    ['-1', undefined],
    ['1.5', undefined],
    ['abc', undefined],
  ])('parseOptionalInt(%j) = %j', (input, expected) => {
    expect(parseOptionalInt(input)).toBe(expected);
  });
});
