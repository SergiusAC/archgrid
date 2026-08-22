import { describe, expect, it } from 'vitest'

import { isKey, KEY, newKey, slugify } from './keys.ts'

/**
 * The server parses every key as a UUID and refuses anything else with a 400. What is tested here
 * is not tidiness — it is whether the editor can create an element at all.
 */
describe('newKey', () => {
  it('produces the canonical UUID form the server parses', () => {
    expect(newKey()).toMatch(KEY)
  })

  it('never hands out the same key twice', () => {
    const keys = new Set(Array.from({ length: 1000 }, () => newKey()))
    expect(keys.size).toBe(1000)
  })
})

describe('isKey', () => {
  it('accepts what the server accepts', () => {
    expect(isKey('8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b')).toBe(true)
  })

  it('rejects the old slugs and derived-line keys', () => {
    expect(isKey('payment-api')).toBe(false)
    expect(isKey('derived:8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6b:serving:8f3a1c2e-4b5d-4e6f-9a0b-1c2d3e4f5a6c')).toBe(false)
    expect(isKey('')).toBe(false)
  })
})

/** The workspace key is the one slug left, and the database has the last word on its shape. */
const SLUG = /^[a-z0-9]+(-[a-z0-9]+)*$/

describe('slugify', () => {
  it('lowercases and joins words with a single dash', () => {
    expect(slugify('Payment API', 'repository', 60)).toBe('payment-api')
    expect(slugify('  Acme   Bank  ', 'repository', 60)).toBe('acme-bank')
  })

  it('collapses punctuation rather than passing it through', () => {
    expect(slugify('Kafka (v3.6) — main!', 'repository', 60)).toBe('kafka-v3-6-main')
  })

  it('never leaves a leading or trailing dash, even when the cut lands on one', () => {
    expect(slugify('«Payment»', 'repository', 60)).toBe('payment')
    // Found by this test: 'aaaa…-b' cut at the dash used to produce 'aaaa…-', which the server
    // refused — the defect that started docs/09.
    expect(slugify('a'.repeat(10) + '-' + 'b'.repeat(10), 'repository', 11)).toBe('aaaaaaaaaa')
    for (let length = 1; length <= 30; length++) {
      expect(slugify('abc-defgh-ij-klmnopq-r', 'repository', length)).toMatch(SLUG)
    }
  })

  it('falls back when nothing survives', () => {
    expect(slugify('Процессинг', 'repository', 60)).toBe('repository')
    expect(slugify('!!!', 'repository', 60)).toBe('repository')
  })
})
