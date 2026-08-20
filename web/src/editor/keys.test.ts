import { describe, expect, it } from 'vitest'

import { slugify, uniqueKey } from './keys.ts'

/**
 * The database has the last word on these: `^[a-z0-9]+(-[a-z0-9]+)*$`, at most 64 characters, as a
 * check constraint. A key that does not match is a command the server refuses, so what is tested
 * here is not tidiness — it is whether the editor can create an element at all.
 */
const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/

describe('slugify', () => {
  it('lowercases and joins words with a single dash', () => {
    expect(slugify('Payment API', 'element', 56)).toBe('payment-api')
    expect(slugify('  Payment   Service  ', 'element', 56)).toBe('payment-service')
  })

  it('collapses punctuation rather than passing it through', () => {
    expect(slugify('Kafka (v3.6) — main!', 'element', 56)).toBe('kafka-v3-6-main')
  })

  it('never leaves a leading or trailing dash', () => {
    expect(slugify('«Payment»', 'element', 56)).toBe('payment')
    expect(slugify('- api -', 'element', 56)).toBe('api')
  })

  it('falls back when a name reduces to nothing', () => {
    // A name written entirely in Cyrillic — or in punctuation — has no characters this can keep.
    // Without the fallback the command would carry an empty key and be refused by the constraint.
    expect(slugify('Процессинг', 'element', 56)).toBe('element')
    expect(slugify('!!!', 'repository', 60)).toBe('repository')
  })

  it('truncates to leave room for the suffix a collision needs', () => {
    const long = slugify('a'.repeat(200), 'element', 56)
    expect(long).toHaveLength(56)
    // 56 plus `-2` stays inside the 64 the database allows.
    expect(`${long}-2`.length).toBeLessThanOrEqual(64)
  })

  it('produces something the database will accept', () => {
    for (const name of ['Payment API', 'Kafka (v3.6) — main!', '«Payment»', 'a'.repeat(200), 'Процессинг']) {
      expect(slugify(name, 'element', 56)).toMatch(SLUG)
    }
  })
})

describe('uniqueKey', () => {
  it('uses the plain slug when nothing holds it', () => {
    expect(uniqueKey('Payment API', {})).toBe('payment-api')
  })

  it('counts up from two, and keeps counting', () => {
    expect(uniqueKey('Payment API', { 'payment-api': true })).toBe('payment-api-2')
    expect(uniqueKey('Payment API', { 'payment-api': true, 'payment-api-2': true })).toBe('payment-api-3')
  })

  it('asks about presence, not about truth', () => {
    // The maps it is given hold elements, and an element is never falsy — but a key whose value
    // happens to be null or undefined is still taken, and reusing it would collide.
    expect(uniqueKey('Payment API', { 'payment-api': undefined })).toBe('payment-api-2')
    expect(uniqueKey('Payment API', { 'payment-api': null })).toBe('payment-api-2')
  })

  it('is not fooled by what every object inherits', () => {
    // `'constructor' in {}` is true, so asking with `in` renamed an element nothing collided with.
    // Found by this test: an element called "Constructor" came out as `constructor-2` on an empty
    // model, and so did "ToString".
    expect(uniqueKey('Constructor', {})).toBe('constructor')
    expect(uniqueKey('toString', {})).toBe('tostring')
    // A real collision still counts, prototype name or not.
    expect(uniqueKey('Constructor', { constructor: true })).toBe('constructor-2')
  })
})
