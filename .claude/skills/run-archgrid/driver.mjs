#!/usr/bin/env node
/**
 * ArchGrid UI driver.
 *
 * Signs in through Keycloak, opens the workspace's graph, and drives the editor. Exists because three real
 * defects — arrowheads that never rendered, an empty minimap, a context menu falling off the
 * window edge — were invisible to `tsc`, to oxlint and to all 55 server tests. The only thing
 * that finds that class of bug is opening the app and looking.
 *
 * Usage, from the repository root:
 *
 *   node .claude/skills/run-archgrid/driver.mjs smoke
 *   node .claude/skills/run-archgrid/driver.mjs smoke --headed --workspace acme
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
const wantedWorkspace = valueOf('--workspace')
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

/**
 * Opens the workspace's graph.
 *
 * A workspace is the repository and holds exactly one graph (ADR-0008), so there is nothing to
 * pick or create once inside — a single workspace lands straight in the editor.
 */
async function openRepository(page) {
  // A single workspace auto-selects, but only once the list has loaded — so give the canvas a
  // chance to appear before concluding that someone has to pick. Checking immediately raced the
  // auto-select and looked for a chooser that was about to disappear.
  const straightIn = await page.waitForSelector('.ag-canvas', { timeout: 10_000 }).catch(() => null)

  if (!straightIn) {
    if (await page.locator('text=No repositories').count()) {
      log('FAIL no workspace exists. Creating one is not exposed yet; seed it:')
      log('     docker exec archgrid-postgres psql -U archgrid -d archgrid \\')
      log('       -c "insert into workspace (id, \\"key\\", name) values (gen_random_uuid(), \'default\', \'Default\')"')
      exit(2)
    }
    const chips = page.locator('.ag-chiprow .ag-chip')
    if (wantedWorkspace) {
      await chips.filter({ hasText: wantedWorkspace }).first().click()
    } else {
      await chips.first().click()
    }
    await page.waitForSelector('.ag-canvas', { timeout: 20_000 })
  }

  // The canvas draws a view, not the repository (ADR-0009). Without one there is only an empty
  // state, so open the first view or make one.
  const existing = page.locator('.ag-tree-item')
  if ((await existing.count()) > 0) {
    await existing.first().click()
  } else {
    page.once('dialog', (dialog) => dialog.accept('UI check'))
    await page.locator('.ag-block-button').first().click()
    await page.waitForTimeout(2500)
  }

  // Auto-layout resolves asynchronously through ELK, then the view refits. Asserting before
  // that settles gives node counts that are right and positions that are all zero.
  await page.waitForTimeout(3000)
}

async function repositoryCount(page) {
  const label = await page.getByRole('tab', { name: /^Repository/ }).textContent()
  return Number(label?.match(/\d+/)?.[0] ?? 0)
}

async function smoke(page) {
  await signIn(page)
  log('signed in as', USER)
  await openRepository(page)

  mkdirSync(outDir, { recursive: true })
  const shot = (name) => page.screenshot({ path: `${outDir}/${name}.png` })

  let nodes = await page.locator('.ag-paper .ag-node').count()
  if (nodes === 0) {
    log('view is empty, adding two elements so there is something to look at')
    await page.getByRole('tab', { name: /^Palette/ }).click()
    for (const label of ['Application Component', 'System Software']) {
      await page.locator('.ag-palette-item', { hasText: new RegExp(`^${label}$`) }).first().click()
      await page.waitForTimeout(2000)
    }
    nodes = await page.locator('.ag-paper .ag-node').count()
  }
  const edges = await page.locator('.ag-paper .ag-link').count()
  await shot('01-canvas')
  check('canvas renders nodes', nodes > 0, `${nodes} nodes, ${edges} edges`)

  // --- relationship notation ---
  const markers = await page.evaluate(() => {
    // JointJS mints its own marker ids into <defs> and references them from the line, so the
    // reference still has to resolve. Nothing in our code writes the url() any more, which is
    // what made url(#url(#id)) possible before.
    const defined = new Set([...document.querySelectorAll('marker')].map((m) => m.id))
    const used = [...document.querySelectorAll('.ag-paper .ag-link .ag-link-line')].map((p) => ({
      start: p.getAttribute('marker-start'),
      end: p.getAttribute('marker-end'),
      dash: getComputedStyle(p).strokeDasharray,
    }))
    const ids = used
      .flatMap((u) => [u.start, u.end])
      .filter((v) => v && v !== 'none')
      .map((v) => v.replace(/^url\(['"]?#/, '').replace(/['"]?\)$/, ''))
    return { defined: [...defined], used, unresolved: ids.filter((id) => !defined.has(id)) }
  })
  if (edges > 0) {
    const isMarker = (v) => Boolean(v) && v !== 'none'
    const withMarker = markers.used.filter((u) => isMarker(u.start) || isMarker(u.end)).length
    check('edges carry arrowheads', withMarker === edges, `${withMarker}/${edges}`)
    // The failure mode worth guarding: a marker reference that points at nothing still draws the
    // line, so the diagram looks merely plain rather than broken. It cost a release once.
    check('every marker reference resolves', markers.unresolved.length === 0, markers.unresolved.join(', '))
  } else {
    log('SKIP notation checks - the repository has no relationships')
  }

  // --- minimap ---
  const minimapNodes = await page.locator('.ag-minimap .ag-node').count()
  check('minimap draws nodes', minimapNodes === nodes, `${minimapNodes} of ${nodes}`)

  // --- fit ---
  const offscreen = await page.evaluate(() => {
    const pane = document.querySelector('.ag-canvas').getBoundingClientRect()
    return [...document.querySelectorAll('.ag-paper .ag-node')].filter((n) => {
      const r = n.getBoundingClientRect()
      return r.right < pane.left || r.left > pane.right || r.bottom < pane.top || r.top > pane.bottom
    }).length
  })
  check('all nodes are inside the viewport after fitView', offscreen === 0, `${offscreen} off-screen`)

  // A node hidden behind another looks like it was never created at all, which is a worse
  // failure than an untidy diagram. Containment is the exception: a container is *supposed* to
  // have its children drawn on top of it, so a box fully inside a container frame is not a
  // collision.
  const overlaps = await page.evaluate(() => {
    const nodes = [...document.querySelectorAll('.ag-paper .ag-node')].map((n) => ({
      box: n.getBoundingClientRect(),
      container: n.classList.contains('ag-node-container'),
    }))
    const encloses = (outer, inner) =>
      outer.left <= inner.left && outer.top <= inner.top &&
      outer.right >= inner.right && outer.bottom >= inner.bottom
    let count = 0
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const a = nodes[i]
        const b = nodes[j]
        const intersects =
          a.box.left < b.box.right && b.box.left < a.box.right &&
          a.box.top < b.box.bottom && b.box.top < a.box.bottom
        if (!intersects) continue
        if (a.container && encloses(a.box, b.box)) continue
        if (b.container && encloses(b.box, a.box)) continue
        count++
      }
    }
    return count
  })
  check('no two nodes overlap', overlaps === 0, `${overlaps} overlapping pairs`)

  // --- right-click inspector ---
  await page.locator('.ag-paper .ag-node').first().click({ button: 'right' })
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
  const before = await page.locator('.ag-paper .ag-node').count()
  // The palette lives behind its own tab now that views have one too.
  await page.getByRole('tab', { name: /^Palette/ }).click()
  await page.locator('.ag-palette-item', { hasText: /^Application Service$/ }).first().click()
  await page.waitForTimeout(2500)
  const created = await page.locator('.ag-paper .ag-node').count()
  // Escape, not a click at a fixed spot: a new element opens its name editor over itself, and
  // the canvas now centres its content, so a fixed point lands *in that input* — where Ctrl+Z
  // rightly undoes the typing rather than the creation.
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
  await page.keyboard.press('Control+z')
  await page.waitForTimeout(2500)
  const undone = await page.locator('.ag-paper .ag-node').count()
  check('Ctrl+Z undoes a creation', created === before + 1 && undone === before, `${before} → ${created} → ${undone}`)

  await page.keyboard.press('Control+Shift+z')
  await page.waitForTimeout(2500)
  const redone = await page.locator('.ag-paper .ag-node').count()
  check('Ctrl+Shift+Z redoes it', redone === created, `${undone} → ${redone}`)

  // --- remove from view must not be a model change ---
  const inRepositoryBefore = await repositoryCount(page)
  await page.locator('.ag-paper .ag-node').first().click()
  await page.keyboard.press('Delete')
  await page.waitForTimeout(2500)
  const onCanvas = await page.locator('.ag-paper .ag-node').count()
  const inRepositoryAfter = await repositoryCount(page)
  await shot('03-removed-from-view')
  check(
    'Delete takes it off the view but leaves it in the repository',
    onCanvas === redone - 1 && inRepositoryAfter === inRepositoryBefore,
    `canvas ${redone} -> ${onCanvas}, repository ${inRepositoryBefore} -> ${inRepositoryAfter}`,
  )

  // --- properties panel: renaming is the one edit with no other route in the UI ---
  await page.locator('.ag-paper .ag-node').first().click()
  await page.waitForSelector('.ag-properties input', { timeout: 5000 })
  const renamed = `Renamed ${Date.now() % 100000}`
  const nameField = page.locator('.ag-properties .ag-field input').first()
  await nameField.fill(renamed)
  await nameField.blur()
  await page.waitForTimeout(2500)
  const onCanvasName = await page.locator('.ag-node .ag-node-label').first().textContent()
  check('renaming from the properties panel reaches the canvas', onCanvasName?.includes('Renamed'), onCanvasName ?? '')
  await shot('04-properties')

  // --- explorer lists the whole model, including what is off the view ---
  await page.getByRole('tab', { name: /^Repository/ }).click()
  await page.waitForSelector('.ag-sidebar-body', { timeout: 5000 })
  const explorer = await page.evaluate(() => ({
    items: document.querySelectorAll('.ag-tree-item').length,
    addable: document.querySelectorAll('.ag-link.ag-tree-tiny').length,
  }))
  const modelSize = await page.evaluate(
    () => Number(document.querySelector('[role="tab"][aria-selected="true"]')?.textContent?.match(/\d+/)?.[0] ?? 0),
  )
  check('explorer lists every element in the repository', explorer.items === modelSize, `${explorer.items} of ${modelSize}`)
  check('elements off the view offer "add to view"', explorer.addable > 0, `${explorer.addable} addable`)

  // Search for the name this run just set, rather than for something the repository may or may
  // not contain — a check that depends on pre-existing data reports a product defect when the
  // only thing wrong is an empty database.
  await page.locator('.ag-sidebar-body input').first().fill(renamed)
  await page.waitForTimeout(400)
  const filtered = await page.locator('.ag-tree-item').count()
  check(
    'explorer search narrows the list',
    filtered > 0 && filtered < explorer.items,
    `"${renamed}" matched ${filtered} of ${explorer.items}`,
  )
  await shot('05-explorer')

  report()
}

function report() {
  const failed = findings.filter((f) => !f.ok)
  log(`--- ${findings.length - failed.length}/${findings.length} checks passed, screenshots in ${outDir}/`)
  if (failed.length > 0) {
    log('LOOK AT THE SCREENSHOTS. A passing count is not the same as a diagram that reads correctly.')
    exit(1)
  }
}

/**
 * The canvas gestures, on a view this run creates for itself.
 *
 * Separate from `smoke` because these need a known-empty view: resizing, nesting and connecting
 * all assert on geometry, and a view someone else has already arranged gives numbers that mean
 * nothing. Every check here stands for a defect that shipped and was found by looking.
 */
async function interactions(page) {
  await signIn(page)
  log('signed in as', USER)
  await openRepository(page)
  mkdirSync(outDir, { recursive: true })

  const viewName = `driver ${process.pid}`
  page.once('dialog', (dialog) => dialog.accept(viewName))
  await page.locator('.ag-block-button').first().click()
  await page.waitForTimeout(2500)

  const addElement = async (label, name) => {
    await page.getByRole('tab', { name: /^Palette/ }).click()
    await page.locator('.ag-palette-item', { hasText: new RegExp(`^${label}$`) }).first().click()
    await page.waitForTimeout(2000)
    if (await page.locator('.ag-node-name-input').count()) {
      await page.locator('.ag-node-name-input').fill(name)
      await page.keyboard.press('Enter')
      await page.waitForTimeout(2500)
    }
  }
  await addElement('Application Component', 'Payment System')
  await addElement('Application Component', 'Payment API')

  /*
   * Elements are addressed by their key, never by index.
   *
   * The canvas brings a dragged element to the front, which reorders the DOM, so `.first()` and
   * `.nth(1)` stop meaning the same element the moment a gesture starts. That cost most of a day:
   * dragging and nesting both worked, and every index-based assertion said they did not.
   */
  const keyOf = (label) =>
    page.evaluate(
      (text) =>
        [...document.querySelectorAll('.ag-paper .ag-node')]
          .find((node) => node.querySelector('.ag-node-label')?.textContent?.trim() === text)
          ?.getAttribute('data-key') ?? null,
      label,
    )
  const nodeAt = (key) => page.locator(`.ag-paper .ag-node[data-key="${key}"]`)
  const system = await keyOf('Payment System')
  const api = await keyOf('Payment API')

  // --- connection points ---
  const handles = await nodeAt(system).locator('.ag-handle').count()
  check('a node offers a connection point on every side', handles === 4, `${handles} handles`)

  // --- resize, and its survival across a reload ---
  await nodeAt(system).click()
  await page.waitForTimeout(400)
  check('selecting an element shows resize handles', (await page.locator('.ag-resize-handle').count()) > 0)

  // Model units, not screen pixels: the fit picks a zoom to suit the content, so the same element
  // measures differently after a reload.
  const resizedId = system
  const flowWidth = (id) =>
    page.evaluate(
      (key) =>
        parseFloat(
          document.querySelector(`.ag-paper .ag-node[data-key="${key}"] .ag-body`).getAttribute('width'),
        ),
      id,
    )
  // The grip itself, rather than a guess at where the corner is.
  const grip = await page.locator('.ag-resize-handle').first().boundingBox()
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2)
  await page.mouse.down()
  await page.mouse.move(grip.x + 220, grip.y + 140, { steps: 12 })
  await page.mouse.up()
  await page.waitForTimeout(1500)
  const grown = await flowWidth(resizedId)
  check('dragging a corner resizes the element', grown > 200, `flow width ${Math.round(grown)}`)

  await page.reload({ waitUntil: 'domcontentloaded' })
  await openRepository(page)
  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3500)
  const afterReload = await flowWidth(resizedId)
  check('the size is persisted', Math.abs(afterReload - grown) < 5, `${Math.round(grown)} -> ${Math.round(afterReload)}`)

  // --- panels ---
  const sidebarWidth = (await page.locator('.ag-sidebar').boundingBox()).width
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(400)
  check('the left panel collapses', (await page.locator('.ag-sidebar').count()) === 0)
  // A collapsed panel must give its space to the canvas. A grid track left behind when the panel
  // unmounted shifted the canvas and both splitters one column along, filling the screen.
  const canvas = await page.locator('.ag-canvas').boundingBox()
  check('the canvas takes the space the panel gave up', canvas.x < 10 && canvas.width > 1000, `x=${Math.round(canvas.x)} w=${Math.round(canvas.width)}`)
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(400)

  const splitter = await page.locator('.ag-splitter-left').boundingBox()
  await page.mouse.move(splitter.x + 3, splitter.y + 300)
  await page.mouse.down()
  await page.mouse.move(splitter.x + 150, splitter.y + 300, { steps: 10 })
  await page.mouse.up()
  await page.waitForTimeout(400)
  const widened = (await page.locator('.ag-sidebar').boundingBox()).width
  check('the left panel resizes by dragging', widened > sidebarWidth + 80, `${Math.round(sidebarWidth)} -> ${Math.round(widened)}`)

  await page.locator('.ag-splitter-right .ag-splitter-toggle').click()
  await page.waitForTimeout(400)
  check('the right panel collapses', (await page.locator('.ag-properties').count()) === 0)
  await page.screenshot({ path: `${outDir}/30-panels.png` })

  // --- nesting ---
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(300)
  const sizeOf = (key) =>
    page.evaluate((k) => {
      const body = document.querySelector(`.ag-paper .ag-node[data-key="${k}"] .ag-body`)
      return { width: Number(body.getAttribute('width')), height: Number(body.getAttribute('height')) }
    }, key)
  const sizeBefore = await sizeOf(system)
  const container = await nodeAt(system).boundingBox()
  const child = await nodeAt(api).boundingBox()
  await page.mouse.move(child.x + child.width / 2, child.y + child.height / 2)
  await page.mouse.down()
  await page.mouse.move(container.x + container.width / 2, container.y + container.height / 2, { steps: 20 })
  await page.mouse.up()
  await page.waitForTimeout(3500)
  check('dropping one element on another nests it', (await page.locator('.ag-paper .ag-node-container').count()) > 0)

  // A container the architect sized on purpose must not be reshaped by what is dropped into it.
  // Fitting it to its contents also shrank it, and the spacing of the whole diagram went with it.
  const sizeAfter = await sizeOf(system)
  check(
    'the container is never shrunk by what is put inside it',
    sizeAfter.width >= sizeBefore.width && sizeAfter.height >= sizeBefore.height,
    `${sizeBefore.width}x${sizeBefore.height} -> ${sizeAfter.width}x${sizeAfter.height}`,
  )

  // Nodes are a flat list in the DOM, so a nested child is a sibling of its container, not a
  // descendant of it — comparing rendered boxes is the only way to know it really sits inside.
  const frame = await nodeAt(system).boundingBox()
  const inner = await nodeAt(api).boundingBox()
  check(
    'the container frame encloses its child',
    inner.x >= frame.x - 1 && inner.y >= frame.y - 1 &&
      inner.x + inner.width <= frame.x + frame.width + 1 &&
      inner.y + inner.height <= frame.y + frame.height + 1,
    `child ${Math.round(inner.width)}x${Math.round(inner.height)} in frame ${Math.round(frame.width)}x${Math.round(frame.height)}`,
  )
  // A container whose own name is covered by its first child reads worse than no nesting at all.
  const label = await nodeAt(system).locator('.ag-node-label').boundingBox()
  check(
    "the container's own name stays clear of its children",
    label.y + label.height <= inner.y + 1,
    `label ends at ${Math.round(label.y + label.height)}, child starts at ${Math.round(inner.y)}`,
  )
  await page.screenshot({ path: `${outDir}/31-nested.png` })

  // --- drawing a relationship ---
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(300)
  await addElement('Application Component', 'Ledger')
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(500)
  // A new element must not land on top of an existing one — least of all on its connection
  // points, which made the element impossible to link to anything.
  const ledgerKey = await keyOf('Ledger')
  const ledger = await nodeAt(ledgerKey).boundingBox()
  const grownFrame = await nodeAt(system).boundingBox()
  const overlapsContainer =
    ledger.x < grownFrame.x + grownFrame.width && grownFrame.x < ledger.x + ledger.width &&
    ledger.y < grownFrame.y + grownFrame.height && grownFrame.y < ledger.y + ledger.height
  check('a new element avoids the container it would otherwise cover', !overlapsContainer)

  // Access is not permitted between two Application Components, which is the teaching path.
  await page.locator('select').first().selectOption({ label: 'Access' })
  await page.waitForTimeout(300)
  const edgesBefore = await page.locator('.ag-paper .ag-link').count()
  const handle = await nodeAt(system).locator('[joint-selector="handle-right"]').boundingBox()
  await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2)
  await page.mouse.down()
  // Released over the middle of the target, not on its 10px dot: aiming at the element is enough.
  await page.mouse.move(ledger.x + ledger.width / 2, ledger.y + ledger.height / 2, { steps: 14 })
  await page.mouse.up()
  await page.waitForTimeout(1200)

  if (await page.locator('.ag-picker').count()) {
    const focused = await page.evaluate(() => document.activeElement?.tagName)
    check('the picker takes focus so a type can be chosen by keyboard', focused === 'BUTTON', `focus on ${focused}`)
    await page.keyboard.press('Enter')
    await page.waitForTimeout(2500)
  }
  await page.screenshot({ path: `${outDir}/32-relationship.png` })
  const edgesAfter = await page.locator('.ag-paper .ag-link').count()
  check('the relationship is drawn', edgesAfter > edgesBefore, `${edgesBefore} -> ${edgesAfter}`)

  // --- the Magic Connector: a line into empty space makes what belongs there ---
  const before = await page.locator('.ag-paper .ag-node').count()
  // Downwards, not to the right: Ledger sits near the right edge, and a drop point outside the
  // window is clamped by the browser, so the check would be measuring the clamp.
  const from = await nodeAt(ledgerKey).locator('[joint-selector="handle-bottom"]').boundingBox()
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  const dropX = from.x + from.width / 2
  const dropY = from.y + 220
  await page.mouse.move(dropX, dropY, { steps: 16 })
  await page.mouse.up()
  await page.waitForTimeout(900)
  check('dropping a line on empty canvas offers what may follow', (await page.locator('.ag-magic').count()) > 0)

  // Only what the metamodel permits from this source, never the full list of 11.
  const offered = await page.locator('.ag-magic li button span').allTextContents()
  check(
    'the menu offers relationships, not a blank list',
    offered.length > 0 && offered.length <= 11,
    offered.join(', '),
  )
  await page.locator('.ag-magic li button', { hasText: 'Serving' }).first().click()
  await page.waitForTimeout(400)
  await page.locator('.ag-magic li button', { hasText: 'Application Service' }).first().click()
  await page.waitForTimeout(3500)
  const after = await page.locator('.ag-paper .ag-node').count()
  check('it creates the element and the relationship together', after === before + 1, `${before} -> ${after}`)

  // The drop point is where the user said it goes; a tidy grid slot elsewhere ignores the gesture.
  const madeKey = await keyOf('Application Service')
  // The body rect, not the node group: the group's box also covers the connection points and the
  // resize grip, which shifts its centre by most of a handle.
  const made = await nodeAt(madeKey).locator('.ag-body').boundingBox()
  /*
   * Relative to the source, not to remembered screen coordinates.
   *
   * The line was dragged straight down from Ledger, so the new element belongs directly below it
   * and roughly on its axis. Comparing against the drop point recorded earlier compares across a
   * canvas that may have panned in between, and reads as a placement bug when the placement is
   * right — it did exactly that.
   */
  const from2 = await nodeAt(ledgerKey).locator('.ag-body').boundingBox()
  check(
    'the new element lands where the line was dropped',
    Math.abs(made.x + made.width / 2 - (from2.x + from2.width / 2)) < 80 && made.y > from2.y + from2.height,
    `centre x ${Math.round(made.x + made.width / 2)} vs source ${Math.round(from2.x + from2.width / 2)}, below: ${made.y > from2.y + from2.height}`,
  )
  void dropX
  void dropY
  await page.keyboard.press('Escape')
  await page.screenshot({ path: `${outDir}/33-magic.png` })

  // --- notation: the shape says what it is before any word is read ---
  const shapes = await page.evaluate(() =>
    [...document.querySelectorAll('.ag-paper .ag-node')].map((node) => ({
      name: node.querySelector('.ag-node-label')?.textContent?.trim(),
      radius: Number(node.querySelector('.ag-body')?.getAttribute('rx') ?? 0),
      icon: (node.querySelector('.ag-node-icon')?.getAttribute('d') ?? '').length,
    })),
  )
  check(
    'every element carries its type glyph',
    shapes.every((shape) => shape.icon > 0),
    `${shapes.filter((s) => s.icon > 0).length} of ${shapes.length}`,
  )
  // A service is a pill and a component is a box. If both come back square, the shape vocabulary
  // is not being applied and the diagram has quietly become a flowchart again.
  const service = shapes.find((shape) => shape.name === 'Application Service')
  const component = shapes.find((shape) => shape.name === 'Payment System')
  check(
    'a service is drawn as a pill, a component as a box',
    service && component && service.radius > component.radius + 20,
    `service rx=${service?.radius}, component rx=${component?.radius}`,
  )
  await page.screenshot({ path: `${outDir}/34-notation.png` })

  report()
}

async function justScreenshot(page) {
  await signIn(page)
  await openRepository(page)
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
  } else if (command === 'interactions') {
    await interactions(page)
  } else if (command === 'shot') {
    await justScreenshot(page)
  } else {
    log(`unknown command '${command}'. Use: smoke | interactions | shot`)
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
