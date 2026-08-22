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
  /** C4 levels, hand-authored beside the shape table: a name and a depth relative to a focus. */
  c4Levels: { code: string; name: string; depth: number }[]
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

/** Straight lines with bendpoints, or orthogonal runs without them — Archi's own two options. */
export type ConnectionRouter = 'straight' | 'orthogonal'

/**
 * One placement: a single drawing of an element on one view.
 *
 * An element may be drawn more than once (ADR-0014) — membership says whether it is on the view,
 * this says how often and where. `id` names the placement, and `parent` is the placement this one
 * is drawn inside, never the element.
 */
export interface LayoutNode {
  id: string
  element: string
  x: number
  y: number
  width: number | null
  height: number | null
  /** The placement this one is drawn inside. Presentation only, per view (ADR-0011). */
  parent: string | null
  /** Which placement is drawn over which. Presentation only. */
  z: number | null
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
  /**
   * Computed: those with both endpoints on the view. Never stored as membership.
   *
   * Stated relationships only. A view does not draw what the model merely implies (ADR-0013).
   */
  relationships: ModelRelationship[]
  layout: LayoutNode[]
  /**
   * Relationships this view declines to draw.
   *
   * The model still asserts them, and they appear on every other view. Presentation, like the
   * coordinates beside them (ADR-0011).
   */
  hidden: string[]
  /** How the lines are routed here; empty for every connection nobody has bent. */
  layoutLinks: LayoutLink[]
  /** Conditions on what this view draws. Versioned with its name and viewpoint. */
  filters: ViewFilter[]
  /** Members the filters leave out, named so a narrowed diagram cannot pass for the whole picture. */
  filtered: { key: string; name: string; type: string }[]
  /** How this view draws its connections. Presentation only, never versioned (ADR-0003). */
  router: ConnectionRouter
}

/**
 * Everywhere an element is used.
 *
 * The views half is what matters before a deletion: "it will go from these four diagrams" is a
 * decision an architect can make, "it will go from everywhere" is one they can only accept.
 */
export interface Usage {
  relationships: ModelRelationship[]
  views: { key: string; name: string }[]
}

/**
 * The graph seen as C4 around one element (ADR-0007).
 *
 * Computed, never stored: no element carries a C4 type, because whether something is a system or a
 * container depends on where the boundary is drawn. `omitted` is part of the answer — C4 has a form
 * for ten of ArchiMate's sixty-two concepts, and a projection that dropped the rest in silence
 * would look complete while hiding half the model.
 */
/**
 * One condition on what a view draws — never on what belongs to it.
 *
 * Versioned with the view's name and viewpoint (ADR-0003): "this diagram is about the technology
 * layer" is a documentation decision. Removing it brings the elements back where they were, because
 * they never left.
 */
/**
 * Where a line bends on one view.
 *
 * Presentation, like the coordinates it travels with: a relationship is the same relationship
 * whichever way its line is drawn, and the same one is drawn on many diagrams — each with its own
 * boxes in the way (ADR-0003).
 */
export interface LayoutLink {
  /** One line on one view. A relationship may be drawn by several (ADR-0014, V15). */
  id: string
  /** What the line stands for: a relationship key, or the computed key of a derived line. */
  key: string
  /** Which placements the line runs between; null while it follows the only one there is. */
  sourceNode: string | null
  targetNode: string | null
  vertices: { x: number; y: number }[]
}

export interface ViewFilter {
  kind: 'layer' | 'property'
  /** The property key; unused for a layer. */
  name: string | null
  /** The layer code, or the property value. Null on a property filter means "has it at all". */
  value: string | null
}

export interface C4Projection {
  level: string
  levelName: string
  focus: string
  elements: ModelElement[]
  relationships: ModelRelationship[]
  derived: DerivedRelationship[]
  omitted: { key: string; name: string; type: string }[]
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

/**
 * What an exchange-file import did — kept, discarded by name (ADR-0015), and lost for want of
 * a place. A silent import looks successful right up to the first comparison with the original.
 */
export interface ImportReport {
  modelName: string | null
  commitId: string
  elements: number
  relationships: number
  views: number
  folders: number
  discarded: { code: string; subject: string; detail: string }[]
  lost: Record<string, number>
  interpreted: string[]
  /** Objects the repository already held under the file's own ids, by name: the file was imported before. */
  skipped: string[]
  /** File elements folded into one of the same name and type, and how many went into each (docs/09). */
  merged: Record<string, number>
  /** Objects renamed with a suffix because something of another kind held the name, as "was → now". */
  renamed: string[]
}

/** The command surface. Mirrors dev.archgrid.domain.model.ModelCommand. */
export type Command =
  | { op: 'create-element'; key: string; type: string; name: string; documentation?: string }
  | { op: 'rename-element'; key: string; name: string }
  | { op: 'set-element-documentation'; key: string; documentation: string | null }
  | { op: 'set-element-property'; key: string; property: string; value: string | null }
  | { op: 'delete-element'; key: string }
  | { op: 'add-relationship'; key: string; type: string; source: string; target: string; name?: string }
  | { op: 'set-relationship-property'; key: string; property: string; value: string | null }
  | { op: 'rename-relationship'; key: string; name: string | null }
  | { op: 'delete-relationship'; key: string }
  // View commands. `key` is the view; `element` is what goes on or off it. Coordinates are
  // deliberately absent — layout has its own endpoint and never travels as a command.
  | { op: 'create-view'; key: string; name: string; viewpoint: string }
  | { op: 'rename-view'; key: string; name: string }
  | { op: 'set-viewpoint'; key: string; viewpoint: string }
  | { op: 'set-view-filters'; key: string; filters: ViewFilter[] }
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
