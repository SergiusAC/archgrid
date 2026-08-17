/** Wire types. Mirrors the server DTOs; regenerate from OpenAPI once that contract settles. */

export interface Workspace {
  id: string
  key: string
  name: string
}

/**
 * A workspace's architecture graph.
 *
 * There is exactly one per workspace and it carries no identifier of its own — the workspace
 * is the repository (ADR-0008). Elements are shared across every view of it, which is what
 * makes "where is this used" answer for the whole landscape rather than for one corner.
 */
export interface Graph {
  workspace: string
  name: string
  headCommitId: string | null
  elements: number
  relationships: number
}

export type Layer =
  | 'strategy'
  | 'business'
  | 'application'
  | 'technology'
  | 'physical'
  | 'motivation'
  | 'implementation-migration'
  | 'other'

export interface ElementType {
  concept: string
  name: string
  layer: Layer
  aspect: 'active-structure' | 'behavior' | 'passive-structure' | 'none'
  c4Shape: 'person' | 'box' | 'boundary' | 'omitted'
}

export interface RelationshipType {
  concept: string
  name: string
  /** Single letter used by the permitted matrix. */
  key: string
  category: 'structural' | 'dependency' | 'dynamic' | 'other'
  strength: number
}

export interface Viewpoint {
  code: string
  name: string
  /** Empty means every concept is allowed — that is how the Layered viewpoint is expressed. */
  concepts: string[]
}

export interface MetamodelDto {
  language: string
  version: string
  elementTypes: ElementType[]
  relationshipTypes: RelationshipType[]
  viewpoints: Viewpoint[]
  /** source concept -> target concept -> permitted relationship keys, e.g. `{"Node": {"Artifact": "aio"}}` */
  permitted: Record<string, Record<string, string>>
}

export interface ModelElement {
  key: string
  /** What to show the user: the profile name where there is one, the concept otherwise. */
  type: string
  conceptType: string
  profile: string | null
  name: string
  documentation: string | null
  properties: Record<string, string>
  parent: string | null
  /** Folder it is filed under, or null for the root. Organisation only. */
  folder?: string | null
}

export interface ModelRelationship {
  key: string
  type: string
  source: string
  target: string
  name: string | null
  properties: Record<string, string>
}

/** A named diagram over the repository. Elements are shown, not owned (ADR-0009). */
export interface ViewSummary {
  key: string
  name: string
  viewpoint: string
  folder: string | null
  elements: number
}

export interface LayoutNode {
  element: string
  x: number
  y: number
  width: number | null
  height: number | null
}

/**
 * A relationship the model implies but nobody drew.
 *
 * Drawn differently from explicit ones on purpose: an architect has to be able to tell what
 * the model says from what merely follows from it. `via` answers "why is this line here".
 */
export interface DerivedRelationship {
  key: string
  type: string
  source: string
  target: string
  via: string[]
}

export interface ViewDetail {
  key: string
  name: string
  viewpoint: string
  folder: string | null
  elements: ModelElement[]
  /** Computed: those with both endpoints on the view. Never stored as membership. */
  relationships: ModelRelationship[]
  /** Implied by chains through elements this view does not show. Also never stored. */
  derived: DerivedRelationship[]
  layout: LayoutNode[]
}

/** Organisation only. A folder means nothing to the metamodel and is not versioned. */
export interface Folder {
  id: string
  name: string
  kind: 'elements' | 'views'
  parent: string | null
}

/** A specialisation defined in a model: "Microservice" as a kind of Application Component. */
export interface Profile {
  name: string
  conceptType: string
  attributes: { key: string; name: string; type: string }[]
}

export interface Commit {
  id: string
  seq: number
  author: string
  message: string | null
  createdAt: string
}

export interface CommitResult {
  commitId: string
  elements: number
  relationships: number
}

/** The command surface. Mirrors dev.archgrid.domain.model.ModelCommand. */
export type Command =
  | { op: 'create-element'; key: string; type: string; name: string; documentation?: string; parent?: string }
  | { op: 'rename-element'; key: string; name: string }
  | { op: 'set-element-documentation'; key: string; documentation: string | null }
  | { op: 'set-element-property'; key: string; property: string; value: string | null }
  | { op: 'move-element'; key: string; parent?: string }
  | { op: 'delete-element'; key: string }
  | { op: 'add-relationship'; key: string; type: string; source: string; target: string; name?: string }
  | { op: 'delete-relationship'; key: string }
  // View commands. `key` is the view; `element` is what goes on or off it. Coordinates are
  // deliberately absent — layout has its own endpoint and never travels as a command.
  | { op: 'create-view'; key: string; name: string; viewpoint: string }
  | { op: 'rename-view'; key: string; name: string }
  | { op: 'set-viewpoint'; key: string; viewpoint: string }
  | { op: 'delete-view'; key: string }
  | { op: 'add-to-view'; key: string; element: string }
  | { op: 'remove-from-view'; key: string; element: string }

export interface CommandRequest {
  baseCommitId: string | null
  message: string
  commands: Command[]
}

/** RFC 9457 problem document, with the violation list the model API adds. */
export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  violations?: { code: string; subject: string; detail: string }[]
  expectedBaseCommitId?: string
  currentHeadCommitId?: string
}
