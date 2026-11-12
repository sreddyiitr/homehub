import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { Listing, ListingSearchResponse } from '../api/client';
import { listing } from './fixtures';

export const API = 'http://api.test';

const all: Listing[] = [
  listing({ listingId: 'LST-0002', price: 625000, beds: 2, address: '202 Sample St' }),
  listing(),
  listing({ listingId: 'LST-0003', price: 1200000, beds: 4, status: 'UNDER_OFFER', address: '303 Placeholder Rd' }),
];

/** Default handlers mimic the backend contract (types come from the OpenAPI spec). */
export const handlers = [
  http.get(`${API}/listings/search`, ({ request }) => {
    const q = new URL(request.url).searchParams;
    const city = q.get('city')?.toLowerCase();
    const items = all.filter((l) => l.city.toLowerCase() === city);
    return HttpResponse.json<ListingSearchResponse>({ items });
  }),
  http.get(`${API}/listings/:id`, ({ params }) => {
    const found = all.find((l) => l.listingId === params.id);
    return found
      ? HttpResponse.json<Listing>(found)
      : HttpResponse.json({ status: 404, title: 'Listing not found' }, { status: 404 });
  }),
];

export const server = setupServer(...handlers);
