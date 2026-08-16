/**
 * Thin API client.
 *
 * Deliberately hand-written and tiny. From phase 2 the server publishes an OpenAPI
 * document and this file is replaced by a generated client — writing an elaborate
 * abstraction now would only be thrown away then.
 */

export interface Workspace {
  id: string
  key: string
  name: string
}

/** RFC 9457 problem document, the error shape the server always returns. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
}

export class ApiError extends Error {
  // Written as explicit fields rather than constructor parameter properties: the
  // generated tsconfig enables `erasableSyntaxOnly`, which keeps the TypeScript strictly
  // type-erasable so it can be stripped rather than compiled.
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

async function request<T>(path: string, accessToken: string): Promise<T> {
  const response = await fetch(path, {
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
  })

  if (!response.ok) {
    let problem: ProblemDetail = { status: response.status }
    try {
      problem = { ...problem, ...((await response.json()) as ProblemDetail) }
    } catch {
      // A non-JSON error body (a proxy timeout page, for instance) is not worth failing on.
    }
    throw new ApiError(response.status, problem)
  }

  return (await response.json()) as T
}

export const api = {
  listWorkspaces: (accessToken: string) => request<Workspace[]>('/api/workspaces', accessToken),
}
