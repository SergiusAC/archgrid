#!/usr/bin/env node
/**
 * ArchGrid UI driver.
 *
 * Signs in through Keycloak, opens a model, and drives the editor. Exists because three real
 * defects — arrowheads that never rendered, an empty minimap, a context menu falling off the
 * window edge — were invisible to `tsc`, to oxlint and to all 55 server tests. The only thing
 * that finds that class of bug is opening the app and looking.
 *
 * Usage, from the repository root:
 *
 *   node .claude/skills/run-archgrid/driver.mjs smoke
 *   node .claude/skills/run-archgrid/driver.mjs smoke --headed --model demo
 *   node .claude/skills/run-archgrid/driver.mjs shot --name my-check
 *
 * Requires PLAYWRIGHT_BROWSERS_PATH=0 — Chromium lives under web/node_modules, not in the
 * user-profile default. See SKILL.md.
 */

import { mkdirSync } from 'node:fs'
import { argv, env, exit } from 'node:process'

// Resolved relative to this file so the driver works from any working directory.
const playwrightUrl = new URL('../../../web/node_modules/playwright/index.mjs', import.meta.url)
const { chromium } = await import(playwrightUrl.href)

const WEB = env.ARCHGRID_WEB ?? 'http://localhost:5173'
const API = env.ARCHGRID_API ?? 'http://localhost:8080'
const IDP = env.ARCHGRID_IDP ?? 'http://localhost:8081'
const USER = env.ARCHGRID_USER ?? 'architect'
const PASSWORD = env.ARCHGRID_PASSWORD ?? 'architect'

const args = argv.slice(2)
const command = args[0] ?? 'smoke'
const headed = args.includes('--headed')
const outDir = valueOf('--out') ?? 'build/ui-check'
const wantedModel = valueOf('--model')
const shotName = valueOf('--name') ?? 'shot'

function valueOf(flag) {
  const index = args.indexOf(flag)
  return index >= 0 ? args[index + 1] : undefined
}

const findings = []
const log = (...parts) => console.log('[archgrid]', ...parts)
function check(label, ok, detail = '') {
  findings.push({ label, ok })
  log(`${ok ? 'OK  ' : 'FAIL'} ${label}${detail ? ' — ' + detail : ''}`)
}

async function preflight() {
  for (const [name, url] of [
    ['web (vite)', WEB],
    ['api (bootRun)', `${API}/actuator/health`],
    ['idp (keycloak)', `${IDP}/realms/archgrid/.well-known/openid-configuration`],
  ]) {
    try {
      const response = await fetch(url)
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
    } catch (error) {
      log(`FAIL ${name} not reachable at ${url}: ${error.message}`)
      log('     Start the stack first — see SKILL.md, "Bring the stack up".')
      exit(2)
    }
  }
  log('preflight ok: web, api and idp all answering')
}

/**
 * Authorization Code + PKCE through Keycloak's own login page.
 *
 * The app never sees the password, so there is no shortcut here — the driver has to fill the
 * IdP's form like a person would.
 */
async function signIn(page) {
  await page.goto(WEB, { waitUntil: 'domcontentloaded' })
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(new RegExp(IDP.replace(/^https?:\/\//, '').replace('.', '\\.')), { timeout: 30_000 })
  await page.fill('#username', USER)
  await page.fill('#password', PASSWORD)
  await page.click('#kc-login')
  await page.waitForURL(new RegExp(WEB.replace(/^https?:\/\//, '').replace('.', '\\.')), { timeout: 30_000 })
}

async function openModel(page) {
  await page.waitForSelector('text=Models', { timeout: 20_000 }).catch(() => {})

  if (await page.locator('text=No workspaces').count()) {
    log('FAIL no workspace exists. The workspace API is read-only; seed one:')
    log('     docker exec archgrid-postgres psql -U archgrid -d archgrid \\')
    log('       -c "insert into workspace (id, \\"key\\", name) values (gen_random_uuid(), \'default\', \'Default\')"')
    exit(2)
  }

  const links = page.locator('.shell ul li button.ag-link')
  const count = await links.count()
  if (wantedModel) {
    await links.filter({ hasText: wantedModel }).first().click()
  } else if (count > 0) {
    await links.first().click()
  } else {
    await page.fill('input[aria-label="New model name"]', 'UI check')
    await page.getByRole('button', { name: 'Create' }).click()
  }

  await page.waitForSelector('.ag-canvas', { timeout: 20_000 })
  // Auto-layout resolves asynchronously through ELK, then the view refits. Asserting before
  // that settles gives node counts that are right and positions that are all zero.
  await page.waitForTimeout(3000)
}

async function smoke(page) {
  await signIn(page)
  log('signed in as', USER)
  await openModel(page)

  mkdirSync(outDir, { recursive: true })
  const shot = (name) => page.screenshot({ path: `${outDir}/${name}.png` })

  let nodes = await page.locator('.ag-node').count()
  if (nodes === 0) {
    log('model is empty, adding two elements so there is something to look at')
    for (const label of ['Application Component', 'System Software']) {
      await page.locator('.ag-palette-item', { hasText: new RegExp(`^${label}$`) }).first().click()
      await page.waitForTimeout(1500)
    }
    nodes = await page.locator('.ag-node').count()
  }
  const edges = await page.locator('.react-flow__edge').count()
  await shot('01-canvas')
  check('canvas renders nodes', nodes > 0, `${nodes} nodes, ${edges} edges`)

  // --- relationship notation ---
  const markers = await page.evaluate(() => {
    const defined = new Set([...document.querySelectorAll('.ag-marker-defs marker')].map((m) => m.id))
    const used = [...document.querySelectorAll('.react-flow__edge-path')].map((p) => ({
      start: p.getAttribute('marker-start'),
      end: p.getAttribute('marker-end'),
      dash: getComputedStyle(p).strokeDasharray,
    }))
    const ids = used
      .flatMap((u) => [u.start, u.end])
      .filter(Boolean)
      .map((v) => v.replace(/^url\(['"]?#/, '').replace(/['"]?\)$/, ''))
    return { defined: [...defined], used, unresolved: ids.filter((id) => !defined.has(id)) }
  })
  if (edges > 0) {
    const withMarker = markers.used.filter((u) => u.start || u.end).length
    check('edges carry arrowheads', withMarker === edges, `${withMarker}/${edges}`)
    // The failure mode worth guarding: React Flow wraps a marker id in url(#…) itself, so
    // passing url(#id) yields url(#url(#id)), which resolves to nothing while the line still
    // draws. Nothing looks broken until you read the DOM.
    check('every marker reference resolves', markers.unresolved.length === 0, markers.unresolved.join(', '))
  } else {
    log('SKIP notation checks — model has no relationships')
  }

  // --- minimap ---
  const minimapNodes = await page.locator('.react-flow__minimap-node').count()
  check('minimap draws nodes', minimapNodes === nodes, `${minimapNodes} of ${nodes}`)

  // --- fit ---
  const offscreen = await page.evaluate(() => {
    const pane = document.querySelector('.ag-canvas').getBoundingClientRect()
    return [...document.querySelectorAll('.ag-node')].filter((n) => {
      const r = n.getBoundingClientRect()
      return r.right < pane.left || r.left > pane.right || r.bottom < pane.top || r.top > pane.bottom
    }).length
  })
  check('all nodes are inside the viewport after fitView', offscreen === 0, `${offscreen} off-screen`)

  // --- right-click inspector ---
  await page.locator('.ag-node').first().click({ button: 'right' })
  await page.waitForSelector('.ag-context', { timeout: 5000 })
  const menu = await page.evaluate(() => {
    const el = document.querySelector('.ag-context')
    const box = el.getBoundingClientRect()
    return {
      actions: [...el.querySelectorAll('.ag-context-actions button')].map((b) =>
        b.textContent.replace(/\s+/g, ' ').trim(),
      ),
      usage: el.querySelector('.ag-context-usage h4')?.textContent ?? '',
      inViewport: box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight,
    }
  })
  await shot('02-context-menu')
  check('context menu offers both delete modes', menu.actions.length === 2, menu.actions.join(' | '))
  check('context menu stays inside the window', menu.inViewport)
  await page.keyboard.press('Escape')

  // --- undo / redo through the keyboard ---
  const before = await page.locator('.ag-node').count()
  await page.locator('.ag-palette-item', { hasText: /^Application Service$/ }).first().click()
  await page.waitForTimeout(2500)
  const created = await page.locator('.ag-node').count()
  await page.locator('.ag-canvas').click({ position: { x: 700, y: 520 } })
  await page.keyboard.press('Control+z')
  await page.waitForTimeout(2500)
  const undone = await page.locator('.ag-node').count()
  check('Ctrl+Z undoes a creation', created === before + 1 && undone === before, `${before} → ${created} → ${undone}`)

  await page.keyboard.press('Control+Shift+z')
  await page.waitForTimeout(2500)
  const redone = await page.locator('.ag-node').count()
  check('Ctrl+Shift+Z redoes it', redone === created, `${undone} → ${redone}`)

  // --- remove from view must not be a model change ---
  await page.locator('.ag-node').first().click()
  await page.keyboard.press('Delete')
  await page.waitForTimeout(800)
  const onCanvas = await page.locator('.ag-node').count()
  const restorable = await page.locator('.ag-hidden-panel button.ag-link').count()
  await shot('03-removed-from-view')
  check(
    'Delete removes from the view and offers it back',
    onCanvas === redone - 1 && restorable > 0,
    `${restorable} restorable`,
  )

  const failed = findings.filter((f) => !f.ok)
  log(`--- ${findings.length - failed.length}/${findings.length} checks passed, screenshots in ${outDir}/`)
  if (failed.length > 0) {
    log('LOOK AT THE SCREENSHOTS. A passing count is not the same as a diagram that reads correctly.')
    exit(1)
  }
}

async function justScreenshot(page) {
  await signIn(page)
  await openModel(page)
  mkdirSync(outDir, { recursive: true })
  await page.screenshot({ path: `${outDir}/${shotName}.png` })
  log(`screenshot written to ${outDir}/${shotName}.png`)
}

await preflight()

const browser = await chromium.launch({ headless: !headed })
const page = await browser.newPage({ viewport: { width: 1600, height: 950 } })
page.on('pageerror', (error) => log('PAGE ERROR', error.message))
page.on('console', (message) => {
  if (message.type() === 'error') log('CONSOLE ERROR', message.text())
})

try {
  if (command === 'smoke') {
    await smoke(page)
  } else if (command === 'shot') {
    await justScreenshot(page)
  } else {
    log(`unknown command '${command}'. Use: smoke | shot`)
    exit(2)
  }
} catch (error) {
  log('DRIVER FAILED:', error.message)
  mkdirSync(outDir, { recursive: true })
  await page.screenshot({ path: `${outDir}/failure.png` }).catch(() => {})
  log(`failure screenshot: ${outDir}/failure.png`)
  exit(1)
} finally {
  await browser.close()
}
