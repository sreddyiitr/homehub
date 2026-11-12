import type { Listing } from './api/client';

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

export function formatPrice(dollars: number): string {
  return usd.format(dollars);
}

const STATUS_LABELS: Record<Listing['status'], string> = {
  DRAFT: 'Draft',
  ACTIVE: 'For sale',
  UNDER_OFFER: 'Under offer',
  SOLD: 'Sold',
};

export function statusLabel(status: Listing['status']): string {
  return STATUS_LABELS[status];
}

/** Parses an optional numeric form field: '' → undefined, otherwise a non-negative integer or undefined. */
export function parseOptionalInt(value: string): number | undefined {
  const trimmed = value.trim();
  if (trimmed === '') return undefined;
  const n = Number(trimmed);
  return Number.isInteger(n) && n >= 0 ? n : undefined;
}
