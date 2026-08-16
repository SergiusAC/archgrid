import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'

import { api } from './api.ts'

/**
 * Phase 0 shell: prove the whole chain end to end — identity provider, bearer token,
 * resource server, database — with as little UI as possible. The editor arrives in
 * phase 3 and replaces everything below.
 */
export default function App() {
  const auth = useAuth()

  const workspaces = useQuery({
    queryKey: ['workspaces'],
    enabled: auth.isAuthenticated && Boolean(auth.user?.access_token),
    queryFn: () => api.listWorkspaces(auth.user!.access_token),
  })

  if (auth.isLoading) {
    return <main className="shell">Signing in…</main>
  }

  if (auth.error) {
    return (
      <main className="shell">
        <h1>ArchGrid</h1>
        <p className="error">Sign-in failed: {auth.error.message}</p>
        <button onClick={() => void auth.signinRedirect()}>Try again</button>
      </main>
    )
  }

  if (!auth.isAuthenticated) {
    return (
      <main className="shell">
        <h1>ArchGrid</h1>
        <p className="muted">Architecture graph as the source of truth.</p>
        <button onClick={() => void auth.signinRedirect()}>Sign in</button>
      </main>
    )
  }

  return (
    <main className="shell">
      <header className="bar">
        <h1>ArchGrid</h1>
        <span className="muted">{auth.user?.profile.preferred_username ?? auth.user?.profile.sub}</span>
        <button onClick={() => void auth.signoutRedirect()}>Sign out</button>
      </header>

      <section>
        <h2>Workspaces</h2>
        {workspaces.isPending && <p className="muted">Loading…</p>}
        {workspaces.isError && <p className="error">{workspaces.error.message}</p>}
        {workspaces.data?.length === 0 && (
          <p className="muted">No workspaces yet. The workspace API becomes writable in phase 2.</p>
        )}
        <ul>
          {workspaces.data?.map((workspace) => (
            <li key={workspace.id}>
              <strong>{workspace.name}</strong> <code>{workspace.key}</code>
            </li>
          ))}
        </ul>
      </section>
    </main>
  )
}
