/**
 * Identities — what the editor addresses an element, a relationship or a view by.
 *
 * One per object, opaque, made here and sent with the command that creates the thing, so a new
 * element can be placed on the canvas and referenced by the next command before the commit comes
 * back. The server holds exactly this value as the object's id (docs/09).
 *
 * This used to derive a readable slug from the name — lowercased, hyphenated, truncated — and the
 * truncation was a defect: a cut that landed on a hyphen produced a key the server refused, and
 * the element silently failed to appear. Nothing is derived from the name any more. A name is an
 * attribute, unique in the repository; the identity is random and never shown.
 */
export function newKey(): string {
  return crypto.randomUUID()
}

/** What an id looks like on the wire — the canonical UUID form. Anything else is not one of ours. */
export const KEY = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export function isKey(value: string): boolean {
  return KEY.test(value)
}

/** How names compare for uniqueness — the server's `ModelGraph.nameKey`: trimmed, case-insensitive. */
export function nameKey(name: string): string {
  return name.trim().toLowerCase()
}

/**
 * The name itself if nobody holds it, otherwise the first free `Name (2)`, `Name (3)`…
 *
 * Names are unique in a repository (ADR-0016), and an element created from the palette is named
 * after its type until the user says otherwise — so the second Application Component has to be
 * called something, and "refused by the server" is not it. The server still has the last word:
 * two people doing this at once get one refusal that names the holder.
 */
export function freeName(name: string, taken: Iterable<string>): string {
  const held = new Set(Array.from(taken, nameKey))
  if (!held.has(nameKey(name))) {
    return name
  }
  for (let suffix = 2; ; suffix++) {
    const candidate = `${name} (${suffix})`
    if (!held.has(nameKey(candidate))) {
      return candidate
    }
  }
}

/**
 * A slug for the one thing that still has one: the workspace, whose key is a URL segment an
 * administrator chooses.
 *
 * The shape is fixed by the database, not by taste: `^[a-z0-9]+(-[a-z0-9]+)*$`, at most 64
 * characters, enforced by a check constraint. Dashes are stripped *after* the cut, never before —
 * a cut that lands on a dash would otherwise leave one trailing, which is exactly the defect that
 * used to lose elements when every object had a slug.
 */
export function slugify(value: string, fallback: string, maxLength: number): string {
  return (
    value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .slice(0, maxLength)
      .replace(/^-+|-+$/g, '') || fallback
  )
}
