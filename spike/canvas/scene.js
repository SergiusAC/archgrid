/**
 * One scene, built identically in both libraries, chosen so that each requirement is visible.
 *
 * Geometry is deliberate: payment-api and ledger share a y, so a straight line between them is
 * horizontal; payment-api and broker share an x, so theirs is vertical; the remaining two run
 * diagonally and cross each other in the middle, which is where a line jump must appear.
 */
export const ELEMENTS = [
  { key: 'payment-system', name: 'Payment System', type: 'Application Component', x: 60, y: 60, w: 300, h: 220, container: true },
  { key: 'payment-api', name: 'Payment API', type: 'Application Component', x: 100, y: 140, w: 200, h: 80, parent: 'payment-system' },
  { key: 'ledger', name: 'Ledger', type: 'Application Component', x: 560, y: 140, w: 200, h: 80 },
  { key: 'notification', name: 'Notification', type: 'Application Component', x: 560, y: 380, w: 200, h: 80 },
  { key: 'broker', name: 'Message Broker', type: 'System Software', x: 100, y: 380, w: 200, h: 80 },
  { key: 'billing', name: 'Billing Service', type: 'Application Service', x: 330, y: 530, w: 200, h: 70, shape: 'oval' },
]

export const LINKS = [
  { source: 'payment-api', target: 'ledger', type: 'Serving', label: 'serves' },
  { source: 'payment-api', target: 'broker', type: 'Flow', label: 'events' },
  { source: 'payment-api', target: 'notification', type: 'Serving', label: 'serves' },
  { source: 'broker', target: 'ledger', type: 'Flow', label: 'replicates' },
  { source: 'billing', target: 'ledger', type: 'Serving', label: 'serves' },
]

export const LAYER = {
  'Application Component': '#b5ffff',
  'Application Service': '#b5ffff',
  'System Software': '#c9ffc9',
}

export function byKey() {
  return Object.fromEntries(ELEMENTS.map((e) => [e.key, e]))
}
