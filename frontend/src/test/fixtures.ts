import type { Listing } from '../api/client';

export function listing(overrides: Partial<Listing> = {}): Listing {
  return {
    listingId: 'LST-0001',
    agentId: 'AGT-001',
    status: 'ACTIVE',
    address: '101 Example Ave',
    city: 'Seattle',
    state: 'WA',
    zip: '98101',
    price: 850000,
    beds: 3,
    baths: 2,
    sqft: 1850,
    description: 'Craftsman near the park.',
    version: 1,
    createdAt: '2026-10-01T10:00:00Z',
    updatedAt: '2026-10-01T10:00:00Z',
    ...overrides,
  };
}
