import type {
  Commit,
  CommitResult,
  CommandRequest,
  MetamodelDto,
  Model,
  ModelElement,
  ModelRelationship,
  ProblemDetail,
  Profile,
  Workspace,
} from './types.ts'

export class ApiError extends Error {
  // Explicit fields rather than constructor parameter properties: tsconfig enables
  // `erasableSyntaxOnly`, so the TypeScript here has to be strippable rather than compiled.
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }

  /** True when the model moved on underneath us and the change needs rebasing. */
  get isStaleBase(): boolean {
    return this.status === 409
  }

  /** True when the server refused the change because the result would be invalid. */
  get isRejected(): boolean {
    return this.status === 422
  }
}

async function request<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    let problem: ProblemDetail = { status: response.status }
    try {
      problem = { ...problem, ...((await response.json()) as ProblemDetail) }
    } catch {
      // A non-JSON error body (a proxy timeout page, say) is not worth failing on.
    }
    throw new ApiError(response.status, problem)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

const encode = encodeURIComponent

export function createApi(token: string) {
  return {
    listWorkspaces: () => request<Workspace[]>('/api/workspaces', token),

    /** Cached hard by the server; the whole language including the permitted matrix. */
    metamodel: () => request<MetamodelDto>('/api/metamodel', token),

    listModels: (workspace: string) => request<Model[]>(`/api/workspaces/${encode(workspace)}/models`, token),

    createModel: (workspace: string, body: { key: string; name: string; description?: string }) =>
      request<Model>(`/api/workspaces/${encode(workspace)}/models`, token, {
        method: 'POST',
        body: JSON.stringify(body),
      }),

    getModel: (workspace: string, model: string) =>
      request<Model>(`/api/workspaces/${encode(workspace)}/models/${encode(model)}`, token),

    elements: (workspace: string, model: string) =>
      request<ModelElement[]>(`/api/workspaces/${encode(workspace)}/models/${encode(model)}/elements`, token),

    relationships: (workspace: string, model: string) =>
      request<ModelRelationship[]>(
        `/api/workspaces/${encode(workspace)}/models/${encode(model)}/relationships`,
        token,
      ),

    profiles: (workspace: string, model: string) =>
      request<Profile[]>(`/api/workspaces/${encode(workspace)}/models/${encode(model)}/profiles`, token),

    commits: (workspace: string, model: string) =>
      request<Commit[]>(`/api/workspaces/${encode(workspace)}/models/${encode(model)}/commits`, token),

    /** The only call that changes a model. Everything else here reads. */
    applyCommands: (workspace: string, model: string, body: CommandRequest) =>
      request<CommitResult>(`/api/workspaces/${encode(workspace)}/models/${encode(model)}/commands`, token, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  }
}

export type Api = ReturnType<typeof createApi>
