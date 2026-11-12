import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { api, unwrap } from './client';

export interface SearchCriteria {
  city: string;
  minPrice?: number;
  maxPrice?: number;
  minBeds?: number;
}

export const PAGE_SIZE = 12;

/**
 * Paged search. useInfiniteQuery maps directly onto DynamoDB pagination:
 * each page's nextToken (an encoded LastEvaluatedKey) is the next page param.
 */
export function useListingSearch(criteria: SearchCriteria | null) {
  return useInfiniteQuery({
    queryKey: ['listings', 'search', criteria],
    enabled: criteria !== null,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap(
        await api.GET('/listings/search', {
          params: { query: { ...criteria!, limit: PAGE_SIZE, nextToken: pageParam } },
        }),
      ),
    getNextPageParam: (last) => last.nextToken,
  });
}

export function useListing(id: string) {
  return useQuery({
    queryKey: ['listings', id],
    queryFn: async () => unwrap(await api.GET('/listings/{id}', { params: { path: { id } } })),
  });
}
