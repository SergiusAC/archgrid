/**
 * Slugs — what humans, URLs and the future Architecture-as-Code format address objects by.
 *
 * A key is generated once from a name and never changes again: renaming `Payment Service` to
 * `Billing` leaves `payment-service` alone, which is what keeps the command log readable and a
 * DSL diff meaningful. So this runs at creation, and nowhere else.
 *
 * The shape is fixed by the database, not by taste: `^[a-z0-9]+(-[a-z0-9]+)*$`, at most 64
 * characters, enforced by a check constraint. Producing something else here means a command the
 * server refuses, which is why the fallback exists — a name written entirely in Cyrillic or in
 * punctuation reduces to nothing at all.
 */
export function slugify(value: string, fallback: string, maxLength: number): string {
  return (
    value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, maxLength) || fallback
  )
}

/**
 * A key nothing else has yet.
 *
 * `taken` is any object keyed by existing keys — the elements map, the views map — so this asks
 * about presence rather than about a list, and adding the second `Payment API` to a model of ten
 * thousand costs nothing.
 */
export function uniqueKey(base: string, taken: Record<string, unknown>, fallback = 'element', maxLength = 56): string {
  const root = slugify(base, fallback, maxLength)
  // `Object.hasOwn`, not `in`: `in` walks the prototype chain, so an element called "Constructor"
  // or "ToString" collided with something no model contains and came out as `constructor-2`.
  if (!Object.hasOwn(taken, root)) {
    return root
  }
  // Suffixed rather than randomised: `payment-api-2` is a key someone can read out over a call,
  // and the sequence is what a person would have written themselves.
  for (let suffix = 2; ; suffix++) {
    const candidate = `${root}-${suffix}`
    if (!Object.hasOwn(taken, candidate)) {
      return candidate
    }
  }
}
