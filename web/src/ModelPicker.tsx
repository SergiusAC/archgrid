import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

import { ApiError, type Api } from './api/client.ts'

interface ModelPickerProps {
  api: Api
  workspace: string | null
  onWorkspace: (key: string) => void
  onModel: (workspace: string, model: string) => void
}

/** Choose a workspace and a model, or create one. Replaced by a proper landing page later. */
export default function ModelPicker({ api, workspace, onWorkspace, onModel }: ModelPickerProps) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  const workspaces = useQuery({ queryKey: ['workspaces'], queryFn: () => api.listWorkspaces() })

  // One workspace is the normal case for an on-premise install; do not make the user pick it.
  useEffect(() => {
    if (!workspace && workspaces.data?.length === 1) {
      onWorkspace(workspaces.data[0].key)
    }
  }, [workspace, workspaces.data, onWorkspace])

  const models = useQuery({
    queryKey: ['models', workspace],
    enabled: workspace != null,
    queryFn: () => api.listModels(workspace!),
  })

  const create = useMutation({
    mutationFn: (modelName: string) =>
      api.createModel(workspace!, { key: slugify(modelName), name: modelName }),
    onSuccess: async (created) => {
      setError(null)
      setName('')
      await queryClient.invalidateQueries({ queryKey: ['models', workspace] })
      onModel(workspace!, created.key)
    },
    onError: (failure: unknown) =>
      setError(
        failure instanceof ApiError && failure.status === 403
          ? 'Creating a model needs the owner role.'
          : String(failure),
      ),
  })

  if (workspaces.isPending) {
    return <main className="shell">Loading workspaces…</main>
  }

  if (!workspaces.data || workspaces.data.length === 0) {
    return (
      <main className="shell">
        <h2>No workspaces</h2>
        <p className="muted">
          The workspace API is read-only until a later phase. Seed the first row by hand — see README.md.
        </p>
      </main>
    )
  }

  return (
    <main className="shell">
      <h2>Workspace</h2>
      <div className="ag-chiprow">
        {workspaces.data.map((option) => (
          <button
            key={option.id}
            type="button"
            className={`ag-chip ${workspace === option.key ? 'ag-chip-active' : ''}`}
            onClick={() => onWorkspace(option.key)}
          >
            {option.name}
          </button>
        ))}
      </div>

      {workspace && (
        <>
          <h2>Models</h2>
          {models.isPending && <p className="muted">Loading…</p>}
          {models.data?.length === 0 && <p className="muted">No models yet.</p>}
          <ul>
            {models.data?.map((option) => (
              <li key={option.id}>
                <button type="button" className="ag-link" onClick={() => onModel(workspace, option.key)}>
                  <strong>{option.name}</strong>
                </button>{' '}
                <code>{option.key}</code>
              </li>
            ))}
          </ul>

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
              placeholder="New model name"
              aria-label="New model name"
            />
            <button type="submit" disabled={create.isPending || !name.trim()}>
              Create
            </button>
          </form>
          {error && <p className="error">{error}</p>}
        </>
      )}
    </main>
  )
}

function slugify(value: string): string {
  return (
    value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60) || 'model'
  )
}
