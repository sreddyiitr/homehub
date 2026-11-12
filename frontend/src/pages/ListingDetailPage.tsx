import { Link, useParams } from 'react-router';
import { ApiError } from '../api/client';
import { useListing } from '../api/listings';
import { formatPrice, statusLabel } from '../format';

export function ListingDetailPage() {
  const { id = '' } = useParams();
  const query = useListing(id);

  return (
    <main className="page">
      <Link to="/" data-action="back-to-search">
        ← Back to search
      </Link>
      {query.isPending && <p role="status">Loading…</p>}
      {query.isError &&
        (query.error instanceof ApiError && query.error.problem.status === 404 ? (
          <p role="alert">This listing doesn't exist or isn't published.</p>
        ) : (
          <p role="alert">Couldn't load this listing.</p>
        ))}
      {query.isSuccess && (
        <article className="detail">
          <h1>{formatPrice(query.data.price)}</h1>
          <span className={`badge badge-${query.data.status.toLowerCase()}`}>{statusLabel(query.data.status)}</span>
          <p>
            {query.data.address}, {query.data.city}, {query.data.state} {query.data.zip}
          </p>
          <p>
            {query.data.beds} bd · {query.data.baths} ba · {query.data.sqft.toLocaleString('en-US')} sqft
          </p>
          {query.data.description && <p>{query.data.description}</p>}
          <p className="muted">Listing {query.data.listingId}</p>
        </article>
      )}
    </main>
  );
}
