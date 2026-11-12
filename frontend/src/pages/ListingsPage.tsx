import { type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ApiError, type Listing } from '../api/client';
import { useListingSearch, type SearchCriteria } from '../api/listings';
import { formatPrice, parseOptionalInt, statusLabel } from '../format';

const DEFAULT_CITY = 'Seattle';

/** Search criteria live in the URL, so a filtered view is shareable and survives reloads. */
function criteriaFrom(params: URLSearchParams): SearchCriteria {
  return {
    city: params.get('city') || DEFAULT_CITY,
    minPrice: parseOptionalInt(params.get('minPrice') ?? ''),
    maxPrice: parseOptionalInt(params.get('maxPrice') ?? ''),
    minBeds: parseOptionalInt(params.get('minBeds') ?? ''),
  };
}

export function ListingsPage() {
  const [params, setParams] = useSearchParams();
  const criteria = criteriaFrom(params);
  const search = useListingSearch(criteria);

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const next = new URLSearchParams();
    for (const key of ['city', 'minPrice', 'maxPrice', 'minBeds']) {
      const v = String(form.get(key) ?? '').trim();
      if (v) next.set(key, v);
    }
    setParams(next);
  }

  const listings = search.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <main className="page">
      <h1>Homes for sale</h1>
      <form key={params.toString()} className="filters" onSubmit={onSubmit} aria-label="Search listings">
        <label>
          City
          <input name="city" defaultValue={criteria.city} required />
        </label>
        <label>
          Min price
          <input name="minPrice" inputMode="numeric" defaultValue={criteria.minPrice ?? ''} />
        </label>
        <label>
          Max price
          <input name="maxPrice" inputMode="numeric" defaultValue={criteria.maxPrice ?? ''} />
        </label>
        <label>
          Min beds
          <input name="minBeds" inputMode="numeric" defaultValue={criteria.minBeds ?? ''} />
        </label>
        <button type="submit" data-action="search-listings">
          Search
        </button>
      </form>

      {search.isPending && <p role="status">Loading listings…</p>}
      {search.isError && (
        <p role="alert">
          Couldn't load listings: {search.error instanceof ApiError ? search.error.message : 'network error'}
        </p>
      )}
      {search.isSuccess && listings.length === 0 && <p>No listings match in {criteria.city}.</p>}

      <ul className="grid">
        {listings.map((l) => (
          <ListingCard key={l.listingId} listing={l} />
        ))}
      </ul>

      {search.hasNextPage && (
        <button
          type="button"
          data-action="load-more-listings"
          onClick={() => search.fetchNextPage()}
          disabled={search.isFetchingNextPage}
        >
          {search.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </main>
  );
}

function ListingCard({ listing }: { listing: Listing }) {
  return (
    <li className="card">
      <div className="photo-placeholder" aria-hidden="true">
        No photo yet
      </div>
      <div className="card-body">
        <span className={`badge badge-${listing.status.toLowerCase()}`}>{statusLabel(listing.status)}</span>
        <Link to={`/listings/${listing.listingId}`} className="price" data-action="open-listing">
          {formatPrice(listing.price)}
        </Link>
        <span>
          {listing.beds} bd · {listing.baths} ba · {listing.sqft.toLocaleString('en-US')} sqft
        </span>
        <span className="muted">
          {listing.address}, {listing.city}
        </span>
      </div>
    </li>
  );
}
