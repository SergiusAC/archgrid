import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

import { ApiError, type Api } from './api/client.ts'
import { slugify } from './editor/keys.ts'

interface WorkspacePickerProps {
  api: Api
  onPick: (workspaceKey: string) => void
  /** True when the user asked to switch, so a single workspace must not auto-select again. */
  explicit?: boolean
}

/**
 * Choose or create a repository.
 *
 * A workspace is the repository and holds exactly one connected graph (ADR-0008). Creating one
 * is a rare, deliberate act — a separate landscape with its own history and its own access
 * control — which is why it needs the admin role and why nothing here is a wizard.
 */
export default function WorkspacePicker({ api, onPick, explicit = false }: WorkspacePickerProps) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  const workspaces = useQuery({ queryKey: ['workspaces'], queryFn: () => api.listWorkspaces() })

  // One workspace is the normal case for an on-premise install; do not make anyone pick it.
  // Unless they came here on purpose to switch, in which case auto-selecting would trap them.
  useEffect(() => {
    if (!explicit && workspaces.data?.length === 1) {
      onPick(workspaces.data[0].key)
    }
  }, [explicit, workspaces.data, onPick])

  const create = useMutation({
    mutationFn: (repositoryName: string) =>
      api.createWorkspace({ key: slugify(repositoryName, 'repository', 60), name: repositoryName }),
    onSuccess: async (created) => {
      setError(null)
      setName('')
      await queryClient.invalidateQueries({ queryKey: ['workspaces'] })
      onPick(created.key)
    },
    onError: (failure: unknown) =>
      setError(
        failure instanceof ApiError && failure.status === 403
          ? 'Creating a repository needs the admin role.'
          : failure instanceof ApiError
            ? failure.message
            : String(failure),
      ),
  })

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

  const list = workspaces.data ?? []

  return (
    <main className="shell">
      <h2>Repository</h2>
      <p className="muted">
        A repository holds one architecture graph. Everything in it — systems, services, Kafka,
        Redis — is shared by every view. Separate landscapes belong in separate repositories.
      </p>

      {list.length === 0 && <p className="muted">None yet.</p>}
      <div className="ag-chiprow">
        {list.map((workspace) => (
          <button key={workspace.id} type="button" className="ag-chip" onClick={() => onPick(workspace.key)}>
            {workspace.name}
          </button>
        ))}
      </div>

      <form
        className="ag-newmodel"
        onSubmit={(event) => {
          event.preventDefault()
          if (name.trim()) {
            create.mutate(name.trim())
          }
        }}
      >
        <input
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="New repository name"
          aria-label="New repository name"
        />
        <button type="submit" disabled={create.isPending || !name.trim()}>
          Create
        </button>
      </form>
      {error && <p className="error">{error}</p>}
    </main>
  )
}


