import type { ElementType, Layer, MetamodelDto, RelationshipType, Viewpoint } from '../api/types.ts'

/**
 * The metamodel, indexed for the questions the editor asks while the mouse is moving.
 *
 * The client copy exists for feedback, never for authority. The server re-validates every
 * command, so a stale or tampered copy here can make the editor unhelpful but cannot put an
 * invalid model into the database (ADR-0004).
 */
export class Metamodel {
  readonly version: string
  readonly elementTypes: ElementType[]
  readonly relationshipTypes: RelationshipType[]
  readonly viewpoints: Viewpoint[]

  private readonly elementByConcept: Map<string, ElementType>
  private readonly relationshipByConcept: Map<string, RelationshipType>
  private readonly relationshipByKey: Map<string, RelationshipType>
  private readonly permitted: Record<string, Record<string, string>>

  constructor(dto: MetamodelDto) {
    this.version = dto.version
    this.elementTypes = dto.elementTypes
    this.relationshipTypes = dto.relationshipTypes
    this.viewpoints = dto.viewpoints
    this.permitted = dto.permitted
    this.elementByConcept = new Map(dto.elementTypes.map((type) => [type.concept, type]))
    this.relationshipByConcept = new Map(dto.relationshipTypes.map((type) => [type.concept, type]))
    this.relationshipByKey = new Map(dto.relationshipTypes.map((type) => [type.key, type]))
  }

  elementType(concept: string): ElementType | undefined {
    return this.elementByConcept.get(concept)
  }

  relationshipType(concept: string): RelationshipType | undefined {
    return this.relationshipByConcept.get(concept)
  }

  /** Every relationship type allowed from one concept to another, in declaration order. */
  permittedBetween(source: string, target: string): RelationshipType[] {
    const keys = this.permitted[source]?.[target] ?? ''
    const types: RelationshipType[] = []
    for (const key of keys) {
      const type = this.relationshipByKey.get(key)
      if (type) {
        types.push(type)
      }
    }
    return types
  }

  permits(source: string, target: string, relationship: string): boolean {
    const type = this.relationshipByConcept.get(relationship)
    if (!type) {
      return false
    }
    return (this.permitted[source]?.[target] ?? '').includes(type.key)
  }

  /**
   * Why a relationship was refused, phrased the way the server phrases it.
   *
   * Kept deliberately close to the server's wording so a user never sees two different
   * explanations of the same rule.
   */
  explain(source: string, target: string, relationship: string): string {
    const sourceName = this.elementType(source)?.name ?? source
    const targetName = this.elementType(target)?.name ?? target
    const relationshipName = this.relationshipType(relationship)?.name ?? relationship
    if (this.permits(source, target, relationship)) {
      return `${relationshipName} from ${sourceName} to ${targetName} is permitted.`
    }
    const alternatives = this.permittedBetween(source, target)
    const head = `${relationshipName} is not permitted from ${sourceName} to ${targetName}.`
    if (alternatives.length === 0) {
      return `${head} No relationship type is permitted between these two.`
    }
    return `${head} Permitted instead: ${alternatives.map((type) => type.name).join(', ')}.`
  }

  viewpoint(code: string): Viewpoint | undefined {
    return this.viewpoints.find((viewpoint) => viewpoint.code === code)
  }

  /**
   * The element types a viewpoint offers, ordered by layer.
   *
   * An empty concept list upstream means every concept is allowed, which is the Layered
   * viewpoint. Junction and Relationship are matrix endpoints rather than elements and never
   * belong in a palette.
   */
  palette(viewpointCode: string): ElementType[] {
    const viewpoint = this.viewpoint(viewpointCode)
    const allowed = viewpoint && viewpoint.concepts.length > 0 ? new Set(viewpoint.concepts) : null
    return this.elementTypes
      .filter((type) => type.concept !== 'Junction' && type.concept !== 'Relationship')
      .filter((type) => !allowed || allowed.has(type.concept))
      .sort((a, b) => layerOrder(a.layer) - layerOrder(b.layer) || a.name.localeCompare(b.name))
  }
}

/** Top-down as ArchiMate draws it, so a palette reads the way the layered viewpoint does. */
const LAYER_ORDER: Layer[] = [
  'strategy',
  'business',
  'application',
  'technology',
  'physical',
  'motivation',
  'implementation-migration',
  'other',
]

export function layerOrder(layer: Layer): number {
  const index = LAYER_ORDER.indexOf(layer)
  return index === -1 ? LAYER_ORDER.length : index
}

export const LAYER_LABELS: Record<Layer, string> = {
  strategy: 'Strategy',
  business: 'Business',
  application: 'Application',
  technology: 'Technology',
  physical: 'Physical',
  motivation: 'Motivation',
  'implementation-migration': 'Implementation & Migration',
  other: 'Other',
}
