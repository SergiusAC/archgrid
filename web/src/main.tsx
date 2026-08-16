import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'

import App from './App.tsx'
import { oidcConfig } from './auth.ts'
import './index.css'

/**
 * Server state lives in TanStack Query; client state will live in a separate store once
 * the editor exists (phase 3). Keeping the two apart from the start is what makes the
 * three-layer split of model / view definition / layout implementable on the client —
 * see ADR-0003.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) =>
        // Never retry an authorization failure: it will fail identically every time and
        // only delays the login prompt.
        !(error instanceof Error && error.name === 'ApiError') && failureCount < 2,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider {...oidcConfig}>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </AuthProvider>
  </StrictMode>,
)
