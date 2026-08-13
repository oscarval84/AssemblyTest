import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'

// Self-hosted rather than loaded from a font CDN: an external request on every
// page load is a dependency, a tracking surface and one more thing between a
// supplier and their checklist.
import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/500.css'
import '@fontsource/ibm-plex-sans/600.css'
import '@fontsource/ibm-plex-sans/700.css'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Status is the product. Refetching when a tab regains focus is what keeps
      // ops from acting on a document that was replaced ten minutes ago.
      refetchOnWindowFocus: true,
      staleTime: 10_000,
      retry: 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
