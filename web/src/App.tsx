import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useAuth } from 'react-oidc-context'

import WorkspacePicker from './WorkspacePicker.tsx'
import { createApi } from './api/client.ts'
import Editor from './editor/Editor.tsx'
import { Metamodel } from './metamodel/Metamodel.ts'

export default function App() {
  const auth = useAuth()
  const [workspace, setWorkspace] = useState<string | null>(null)
  // Distinguishes "nothing chosen yet" from "chose to switch": with one repository the picker
  // auto-selects, and without this the switch link would bounce straight back in.
  const [switching, setSwitching] = useState(false)

  const token = auth.user?.access_token
  const api = useMemo(() => (token ? createApi(token) : null), [token])

  const metamodelQuery = useQuery({
    queryKey: ['metamodel'],
    enabled: api != null,
    // The language does not change between releases, so there is no reason to refetch it.
    staleTime: Infinity,
    queryFn: async () => new Metamodel(await api!.metamodel()),
  })

  const profilesQuery = useQuery({
    queryKey: ['profiles', workspace],
    enabled: api != null && workspace != null,
    queryFn: () => api!.profiles(workspace!),
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

  if (!auth.isAuthenticated || !api) {
    return (
      <main className="shell">
        <h1>ArchGrid</h1>
        <p className="muted">Architecture graph as the source of truth.</p>
        <button onClick={() => void auth.signinRedirect()}>Sign in</button>
      </main>
    )
  }

  return (
    <div className="ag-app">
      <header className="ag-appbar">
        <strong>ArchGrid</strong>
        {metamodelQuery.data && (
          <span className="ag-muted">ArchiMate {metamodelQuery.data.version} compatible</span>
        )}
        {workspace && (
          <span className="ag-breadcrumb">
            {workspace}
            <button type="button" className="ag-link" onClick={() => setWorkspace(null)}>
              change
            </button>
          </span>
        )}
        <span className="ag-toolbar-spacer" />
        <span className="ag-muted">{auth.user?.profile.preferred_username ?? auth.user?.profile.sub}</span>
        <button type="button" onClick={() => void auth.signoutRedirect()}>
          Sign out
        </button>
      </header>

      {!metamodelQuery.data ? (
        <main className="shell">
          {metamodelQuery.isError ? (
            <p className="error">Could not load the metamodel: {String(metamodelQuery.error)}</p>
          ) : (
            <p className="muted">Loading the metamodel…</p>
          )}
        </main>
      ) : workspace ? (
        <Editor
          api={api}
          workspace={workspace}
          metamodel={metamodelQuery.data}
          profiles={profilesQuery.data ?? []}
        />
      ) : (
        <WorkspacePicker
          api={api}
          explicit={switching}
          onPick={(key) => {
            setWorkspace(key)
            setSwitching(false)
          }}
        />
      )}
    </div>
  )
}
