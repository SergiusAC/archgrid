import type {
  Commit,
  CommitResult,
  CommandRequest,
  Folder,
  Graph,
  ConnectionRouter,
  LayoutNode,
  MetamodelDto,
  ModelElement,
  ModelRelationship,
  ProblemDetail,
  Profile,
  ViewDetail,
  ViewSummary,
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

  // An empty body, not only a 204: a handler returning void answers 200 with nothing in it, and
  // calling json() on that throws. Harmless while nobody awaited the result — and the moment one
  // caller did, the save that had "always worked" started failing the gesture around it.
  const body = await response.text()
  return (body ? JSON.parse(body) : undefined) as T
}

const encode = encodeURIComponent

export function createApi(token: string) {
  return {
    listWorkspaces: () => request<Workspace[]>('/api/workspaces', token),

    /** Admin-only: a repository is a tenancy boundary with its own history and access control. */
    createWorkspace: (body: { key: string; name: string }) =>
      request<Workspace>('/api/workspaces', token, { method: 'POST', body: JSON.stringify(body) }),

    /** Cached hard by the server; the whole language including the permitted matrix. */
    metamodel: () => request<MetamodelDto>('/api/metamodel', token),

    /** The workspace's graph: name, head commit and how much is in it. Provisioned on first read. */
    graph: (workspace: string) => request<Graph>(`/api/workspaces/${encode(workspace)}`, token),

    elements: (workspace: string) =>
      request<ModelElement[]>(`/api/workspaces/${encode(workspace)}/elements`, token),

    relationships: (workspace: string) =>
      request<ModelRelationship[]>(`/api/workspaces/${encode(workspace)}/relationships`, token),

    profiles: (workspace: string) => request<Profile[]>(`/api/workspaces/${encode(workspace)}/profiles`, token),

    views: (workspace: string) => request<ViewSummary[]>(`/api/workspaces/${encode(workspace)}/views`, token),

    view: (workspace: string, view: string) =>
      request<ViewDetail>(`/api/workspaces/${encode(workspace)}/views/${encode(view)}`, token),

    /** A PUT, not a command: coordinates are the one layer outside the commit log (ADR-0003). */
    saveLayout: (
      workspace: string,
      view: string,
      nodes: LayoutNode[],
      router?: ConnectionRouter,
      hidden?: string[],
    ) =>
      request<void>(`/api/workspaces/${encode(workspace)}/views/${encode(view)}/layout`, token, {
        method: 'PUT',
        // `router` and `hidden` only when they are being sent: a drag saves coordinates several
        // times a second and must not carry an opinion about routing, or about which lines this
        // view leaves out.
        body: JSON.stringify({ nodes, ...(router ? { router } : {}), ...(hidden ? { hidden } : {}) }),
      }),

    folders: (workspace: string, kind: 'elements' | 'views') =>
      request<Folder[]>(`/api/workspaces/${encode(workspace)}/folders?kind=${kind}`, token),

    createFolder: (workspace: string, kind: 'elements' | 'views', name: string, parent?: string | null) =>
      request<Folder>(`/api/workspaces/${encode(workspace)}/folders?kind=${kind}`, token, {
        method: 'POST',
        body: JSON.stringify({ name, parent: parent ?? null }),
      }),

    deleteFolder: (workspace: string, id: string) =>
      request<void>(`/api/workspaces/${encode(workspace)}/folders/${encode(id)}`, token, { method: 'DELETE' }),

    assignFolder: (workspace: string, kind: 'elements' | 'views', key: string, folder: string | null) =>
      request<void>(
        `/api/workspaces/${encode(workspace)}/folders/assign?kind=${kind}&key=${encode(key)}` +
          (folder ? `&folder=${encode(folder)}` : ''),
        token,
        { method: 'PUT' },
      ),

    commits: (workspace: string) => request<Commit[]>(`/api/workspaces/${encode(workspace)}/commits`, token),

    /** The only call that changes the graph. Everything else here reads. */
    applyCommands: (workspace: string, body: CommandRequest) =>
      request<CommitResult>(`/api/workspaces/${encode(workspace)}/commands`, token, {
        method: 'POST',
        body: JSON.stringify(body),
      }),
  }
}

export type Api = ReturnType<typeof createApi>
