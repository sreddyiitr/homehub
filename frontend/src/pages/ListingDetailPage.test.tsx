import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { renderApp } from '../test/render';
import { API, server } from '../test/server';

describe('ListingDetailPage (R-FE-02, slice)', () => {
  it('R-FE-02.1 shows the listing details', async () => {
    renderApp('/listings/LST-0001');

    expect(await screen.findByRole('heading', { name: '$850,000' })).toBeInTheDocument();
    expect(screen.getByText('101 Example Ave, Seattle, WA 98101')).toBeInTheDocument();
    expect(screen.getByText('Craftsman near the park.')).toBeInTheDocument();
    expect(screen.getByText('For sale')).toBeInTheDocument();
  });

  it('R-FE-02.2 is reachable from the grid', async () => {
    renderApp('/');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: '$850,000' }));

    expect(await screen.findByText('Listing LST-0001')).toBeInTheDocument();
  });

  it('R-FE-02.3 explains a 404 (unknown or DRAFT listing)', async () => {
    renderApp('/listings/LST-0004');

    expect(await screen.findByRole('alert')).toHaveTextContent("This listing doesn't exist or isn't published.");
  });

  it('R-FE-02.4 shows a generic error for other failures', async () => {
    server.use(http.get(`${API}/listings/:id`, () => HttpResponse.json({ status: 500 }, { status: 500 })));
    renderApp('/listings/LST-0001');

    expect(await screen.findByRole('alert')).toHaveTextContent("Couldn't load this listing.");
  });
});
