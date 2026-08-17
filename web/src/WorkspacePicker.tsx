import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'

import type { Api } from './api/client.ts'

interface WorkspacePickerProps {
  api: Api
  onPick: (workspaceKey: string) => void
}

/**
 * Choose a repository.
 *
 * A workspace is the repository and holds exactly one connected graph (ADR-0008), so there is
 * nothing to create or name here — pick the repository and you are in it. The many named
 * things an architect makes are views over that graph, and they arrive in phase 4.
 */
export default function WorkspacePicker({ api, onPick }: WorkspacePickerProps) {
  const workspaces = useQuery({ queryKey: ['workspaces'], queryFn: () => api.listWorkspaces() })

  // One workspace is the normal case for an on-premise install; do not make anyone pick it.
  useEffect(() => {
    if (workspaces.data?.length === 1) {
      onPick(workspaces.data[0].key)
    }
  }, [workspaces.data, onPick])

  if (workspaces.isPending) {
    return <main className="shell">Loading repositories…</main>
  }

  if (workspaces.isError) {
    return (
      <main className="shell">
        <p className="error">Could not load repositories: {String(workspaces.error)}</p>
      </main>
    )
  }

  if (!workspaces.data || workspaces.data.length === 0) {
    return (
      <main className="shell">
        <h2>No repositories</h2>
        <p className="muted">
          Creating a workspace is not exposed yet. Seed the first row by hand — see README.md.
        </p>
      </main>
    )
  }

  return (
    <main className="shell">
      <h2>Repository</h2>
      <p className="muted">
        A repository holds one architecture graph. Everything in it — systems, services, Kafka,
        Redis — is shared by every view.
      </p>
      <div className="ag-chiprow">
        {workspaces.data.map((workspace) => (
          <button key={workspace.id} type="button" className="ag-chip" onClick={() => onPick(workspace.key)}>
            {workspace.name}
          </button>
        ))}
      </div>
    </main>
  )
}
