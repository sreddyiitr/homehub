import { Route, Routes } from 'react-router';
import { ListingDetailPage } from './pages/ListingDetailPage';
import { ListingsPage } from './pages/ListingsPage';

export function App() {
  return (
    <Routes>
      <Route path="/" element={<ListingsPage />} />
      <Route path="/listings/:id" element={<ListingDetailPage />} />
    </Routes>
  );
}
