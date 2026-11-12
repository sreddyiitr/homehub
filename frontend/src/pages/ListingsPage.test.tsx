import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { ListingSearchResponse } from '../api/client';
import { listing } from '../test/fixtures';
import { renderApp } from '../test/render';
import { API, server } from '../test/server';

describe('ListingsPage (R-FE-01)', () => {
  it('R-FE-01.1 shows Seattle listings by default, in API order, with status badges', async () => {
    renderApp('/');

    const cards = await screen.findAllByRole('listitem');
    expect(cards).toHaveLength(3);
    expect(within(cards[0]!).getByRole('link')).toHaveTextContent('$625,000');
    expect(within(cards[2]!).getByText('Under offer')).toBeInTheDocument();
    expect(within(cards[1]!).getByText('3 bd · 2 ba · 1,850 sqft')).toBeInTheDocument();
  });

  it('R-FE-01.2 sends the filters to the API and puts them in the URL', async () => {
    let seen: URLSearchParams | undefined;
    server.use(
      http.get(`${API}/listings/search`, ({ request }) => {
        seen = new URL(request.url).searchParams;
        return HttpResponse.json<ListingSearchResponse>({ items: [] });
      }),
    );
    renderApp('/');
    const user = userEvent.setup();

    const city = await screen.findByLabelText('City');
    await user.clear(city);
    await user.type(city, 'Portland');
    await user.type(screen.getByLabelText('Min price'), '400000');
    await user.type(screen.getByLabelText('Min beds'), '2');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(await screen.findByText('No listings match in Portland.')).toBeInTheDocument();
    expect(seen?.get('city')).toBe('Portland');
    expect(seen?.get('minPrice')).toBe('400000');
    expect(seen?.get('minBeds')).toBe('2');
    expect(seen?.has('maxPrice')).toBe(false);
  });

  it('R-FE-01.3 reads criteria from the URL and ignores invalid numbers', async () => {
    let seen: URLSearchParams | undefined;
    server.use(
      http.get(`${API}/listings/search`, ({ request }) => {
        seen = new URL(request.url).searchParams;
        return HttpResponse.json<ListingSearchResponse>({ items: [] });
      }),
    );
    renderApp('/?city=Austin&maxPrice=600000&minBeds=abc');

    await screen.findByText('No listings match in Austin.');
    expect(seen?.get('maxPrice')).toBe('600000');
    expect(seen?.has('minBeds')).toBe(false);
    expect(screen.getByLabelText('Max price')).toHaveValue('600000');
  });

  it('R-FE-01.4 loads the next page using nextToken', async () => {
    server.use(
      http.get(`${API}/listings/search`, ({ request }) => {
        const token = new URL(request.url).searchParams.get('nextToken');
        return token === 'page-2'
          ? HttpResponse.json<ListingSearchResponse>({ items: [listing({ listingId: 'LST-0009', price: 990000 })] })
          : HttpResponse.json<ListingSearchResponse>({ items: [listing()], nextToken: 'page-2' });
      }),
    );
    renderApp('/');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('$990,000')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('R-FE-01.5 shows the API problem detail on errors', async () => {
    server.use(
      http.get(`${API}/listings/search`, () =>
        HttpResponse.json({ status: 400, title: 'Invalid request', detail: 'Request validation failed' }, { status: 400 }),
      ),
    );
    renderApp('/');

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load listings: Request validation failed");
  });

  it('R-FE-01.6 shows a generic message when the network fails', async () => {
    server.use(http.get(`${API}/listings/search`, () => HttpResponse.error()));
    renderApp('/');

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load listings: network error");
  });
});
