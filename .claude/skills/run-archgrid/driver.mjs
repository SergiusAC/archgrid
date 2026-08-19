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

import { mkdirSync, readFileSync, statSync } from 'node:fs'
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

/**
 * Carries a type from the palette onto the canvas.
 *
 * The palette is picked up and put down rather than clicked, so the element lands where it was
 * released. Pointer events, not HTML5 drag-and-drop, which is what the editor implements.
 */
async function dragFromPalette(page, label, at) {
  await page.getByRole('tab', { name: /^Palette/ }).click()
  const item = page.locator('.ag-palette-item', { hasText: new RegExp(`^${label}$`) }).first()
  // The palette scrolls: a type in the technology layer sits below the fold, and its box is then
  // a point outside the window that the mouse cannot reach.
  await item.scrollIntoViewIfNeeded()
  const from = await item.boundingBox()
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(at.x, at.y, { steps: 12 })
  await page.mouse.up()
  await page.waitForTimeout(2500)
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

  /*
   * Its own view, with its own two elements and a line between them.
   *
   * It used to open whichever view came first and assert on whatever was in it. That view has
   * accumulated the leavings of every run ever made, so "no two nodes overlap" was reporting on
   * elements placed by a driver months of runs ago — a check that fails for reasons unrelated to
   * the change under test teaches you to ignore it.
   */
  page.once('dialog', (dialog) => dialog.accept(`smoke ${process.pid}`))
  await page.locator('.ag-block-button').first().click()
  await page.waitForTimeout(2500)
  const canvasBox = await page.locator('.ag-canvas').boundingBox()
  let column = 0
  for (const label of ['Application Component', 'System Software']) {
    await dragFromPalette(page, label, {
      x: canvasBox.x + 260 + column * 300,
      y: canvasBox.y + 260,
    })
    await page.keyboard.press('Escape')
    column += 1
  }
  await page.waitForTimeout(1500)

  // A relationship, so the notation checks have something to read.
  const keys = await page.evaluate(() =>
    [...document.querySelectorAll('.ag-paper .ag-node')].map((node) => node.getAttribute('data-key')),
  )
  const bodyOf = (key) => page.locator(`.ag-paper .ag-node[data-key="${key}"] .ag-body`)
  await page.getByRole('button', { name: /^Connect/ }).click()
  for (const key of keys.slice(0, 2)) {
    const box = await bodyOf(key).boundingBox()
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2)
    await page.waitForTimeout(400)
  }
  if (await page.locator('.ag-picker').count()) {
    await page.keyboard.press('Enter')
  }
  await page.waitForTimeout(2500)

  const nodes = await page.locator('.ag-paper .ag-node').count()
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
  // The views an element is on come from the server, so the menu is not finished the moment it
  // opens. Checking the delete wording before that answer lands would check the wrong sentence.
  await page.waitForFunction(
    () => /On \d+ view/.test(document.querySelector('.ag-context-views h4')?.textContent ?? ''),
    { timeout: 5000 },
  )
  const menu = await page.evaluate(() => {
    const el = document.querySelector('.ag-context')
    const box = el.getBoundingClientRect()
    return {
      // Direct children only: the stacking pair sits in its own row above these two.
      actions: [...el.querySelectorAll('.ag-context-actions > button')].map((b) =>
        b.textContent.replace(/\s+/g, ' ').trim(),
      ),
      rows: [...el.querySelectorAll('.ag-context-actions .ag-context-row button')].map((b) =>
        b.textContent.trim(),
      ),
      usage: el.querySelector('.ag-context-usage h4')?.textContent ?? '',
      views: el.querySelector('.ag-context-views h4')?.textContent ?? '',
      inViewport: box.left >= 0 && box.top >= 0 && box.right <= innerWidth && box.bottom <= innerHeight,
    }
  })
  await shot('02-context-menu')
  check('the menu says which views show the element', /On \d+ view/.test(menu.views), menu.views)
  check(
    'and what deleting it would cost',
    /Also .*removes it from \d+ view/.test(menu.actions[1] ?? ''),
    menu.actions[1] ?? '',
  )
  check('context menu offers both delete modes', menu.actions.length === 2, menu.actions.join(' | '))
  check('context menu stays inside the window', menu.inViewport)
  check(
    'context menu offers copying, duplicating and stacking',
    menu.rows.join(' | ') === 'Copy | Duplicate | Bring to front | Send to back',
    menu.rows.join(' | '),
  )
  await page.keyboard.press('Escape')

  // --- undo / redo through the keyboard ---
  const before = await page.locator('.ag-paper .ag-node').count()
  // The palette lives behind its own tab now that views have one too.
  const room = await page.locator('.ag-canvas').boundingBox()
  await dragFromPalette(page, 'Application Service', { x: room.x + 700, y: room.y + 560 })
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
  // Saving is a press now, not a lost focus: a click elsewhere used to be an entry in the history.
  await page.locator('.ag-props-actions button', { hasText: 'Save' }).click()
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

  let dropColumn = 0
  const addElement = async (label, name) => {
    const canvas = await page.locator('.ag-canvas').boundingBox()
    await dragFromPalette(page, label, {
      x: canvas.x + 220 + (dropColumn % 3) * 320,
      y: canvas.y + 200 + Math.floor(dropColumn / 3) * 220,
    })
    dropColumn += 1
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
  /*
   * Clicks an element with the mouse rather than through the locator.
   *
   * Playwright's actionability check refuses a click whose point is covered by anything — and a
   * container is covered by the element nested in it, which is the normal state of a diagram. The
   * mouse is what a person uses, and JointJS reads the same event either way.
   */
  const clickNode = async (key, where = 'corner') => {
    const box = await nodeAt(key).locator('.ag-body').boundingBox()
    await page.mouse.click(
      where === 'corner' ? box.x + 14 : box.x + box.width / 2,
      where === 'corner' ? box.y + 12 : box.y + box.height / 2,
    )
  }

  const system = await keyOf('Payment System')
  const api = await keyOf('Payment API')

  // --- no handles on the element: connecting is a tool, as in Archi ---
  check('an element carries no connection dots', (await page.locator('.ag-paper .ag-handle').count()) === 0)

  // --- resize, and its survival across a reload ---
  await clickNode(system)
  await page.waitForTimeout(400)
  // Eight, as Archi has: one corner grip means widening a box on the left is two operations.
  check(
    'selecting an element shows all eight resize grips',
    (await page.locator('.ag-resize-handle').count()) === 8,
    `${await page.locator('.ag-resize-handle').count()} grips`,
  )

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
  // The bottom-right grip by name: the grips are in a fixed order and the first one shrinks.
  const grip = await page.locator('.ag-resize-handle[data-grip="bottom-right"]').boundingBox()
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2)
  await page.mouse.down()
  await page.mouse.move(grip.x + 220, grip.y + 140, { steps: 12 })
  await page.mouse.up()
  await page.waitForTimeout(1500)
  const grown = await flowWidth(resizedId)
  check('dragging a corner resizes the element', grown > 200, `flow width ${Math.round(grown)}`)

  // A draft survives a reload on its own, so save first: the claim being checked is that the
  // *server* kept the size, not that the browser remembered it.
  await page.keyboard.press('Control+s')
  await page.waitForTimeout(2000)
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
  /*
   * Nesting asks what it means, and then stops drawing what it now says.
   *
   * Archi's ARM: containment is a way of drawing a structural relationship, so the editor offers
   * to create one, and the line is hidden because the nesting is already saying it. The
   * relationship is in the model - hidden here, drawn on any view where the two are not nested.
   */
  check('nesting asks what it means', (await page.locator('.ag-picker').count()) > 0)
  const nestable = await page.locator('.ag-picker li button span').allTextContents()
  check(
    'it offers only what nesting can express',
    nestable.length > 0 &&
      nestable.every((name) => ['Composition', 'Aggregation', 'Assignment', 'Realization'].includes(name)),
    nestable.join(', '),
  )
  const linksBeforeNesting = await page.locator('.ag-paper .ag-link').count()
  await page.locator('.ag-picker li button', { hasText: 'Composition' }).first().click()
  await page.waitForTimeout(3000)
  check(
    'the relationship it creates is not also drawn as a line',
    (await page.locator('.ag-paper .ag-link').count()) === linksBeforeNesting,
    `${linksBeforeNesting} lines before, ${await page.locator('.ag-paper .ag-link').count()} after`,
  )

  // A container whose own name is covered by its first child reads worse than no nesting at all.
  const label = await nodeAt(system).locator('.ag-node-label').boundingBox()
  check(
    "the container's own name stays clear of its children",
    label.y + label.height <= inner.y + 1,
    `label ends at ${Math.round(label.y + label.height)}, child starts at ${Math.round(inner.y)}`,
  )
  await page.screenshot({ path: `${outDir}/31-nested.png` })

  /*
   * --- a child can be centred in the box that holds it ---
   *
   * Reported from use: the guides made this impossible. The container was excluded from the
   * candidates, so the one alignment wanted had no guide, while unrelated elements nearby went on
   * tugging the child off centre.
   */
  const frameBox = await nodeAt(system).locator('.ag-body').boundingBox()
  const childBox = await nodeAt(api).locator('.ag-body').boundingBox()
  const wantCentre = frameBox.x + frameBox.width / 2
  await page.mouse.move(childBox.x + childBox.width / 2, childBox.y + childBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(wantCentre, childBox.y + childBox.height / 2, { steps: 12 })
  await page.waitForTimeout(250)
  check('centring a child shows the guide that says so', (await page.locator('.ag-guide').count()) > 0)
  await page.mouse.up()
  await page.waitForTimeout(1200)
  const centred = await nodeAt(api).locator('.ag-body').boundingBox()
  // Within half a grid step: aiming at the centre lands on it, and nothing drags it off again.
  // The guide advises now — it used to pull the element flush, which made centring impossible
  // because every other element nearby was pulling too.
  check(
    'and the child lands where it was aimed',
    Math.abs(centred.x + centred.width / 2 - wantCentre) <= 6,
    `child centre ${Math.round(centred.x + centred.width / 2)} vs container centre ${Math.round(wantCentre)}`,
  )
  await page.screenshot({ path: `${outDir}/38-centred.png` })

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

  // No type is chosen in advance any more: two Application Components permit several, so the
  // connector asks — which is the teaching path.
  const edgesBefore = await page.locator('.ag-paper .ag-link').count()
  // Click the tool, click the source, click the target — Archi's sequence.
  await page.getByRole('button', { name: /^Connect/ }).click()
  await clickNode(system)
  await page.waitForTimeout(300)
  await clickNode(ledgerKey, 'centre')
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
  // The connector tool again, but the second click lands on empty canvas.
  const from = await nodeAt(ledgerKey).locator('.ag-body').boundingBox()
  await page.getByRole('button', { name: /^Connect/ }).click()
  await clickNode(ledgerKey, 'centre')
  await page.waitForTimeout(300)
  const dropX = from.x + from.width / 2
  const dropY = from.y + 220
  await page.mouse.click(dropX, dropY)
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

  /*
   * --- orthogonal anchors: aligned elements are joined by a straight run ---
   *
   * The line leaves from the middle of the side facing where it is going, so two elements that
   * line up are joined by an exactly horizontal or vertical line with nobody arranging it. That
   * is the whole claim, and it is checked on the path data rather than by eye.
   */
  const runs = await page.evaluate(() =>
    [...document.querySelectorAll('.ag-paper .ag-link .ag-link-line')]
      .map((path) => (path.getAttribute('d') ?? '').trim().replace(/\s+/g, ' '))
      .map((d) => {
        const n = d.match(/-?[\d.]+/g)?.map(Number) ?? []
        if (n.length < 4) return 'none'
        const [x1, y1] = n
        const [x2, y2] = n.slice(-2)
        // A unit and a half, not half a unit: a click's position comes from screen coordinates
        // through the zoom, so two elements the eye reads as aligned can differ by a fraction.
        if (Math.abs(x1 - x2) < 1.5) return 'vertical'
        if (Math.abs(y1 - y2) < 1.5) return 'horizontal'
        return 'diagonal'
      }),
  )
  check(
    'elements that line up are joined by a straight run',
    runs.some((run) => run === 'vertical' || run === 'horizontal'),
    runs.join(', '),
  )

  /*
   * --- editing quickly must not look like someone else editing ---
   *
   * The optimistic check refuses a command whose base is not the current head. Nothing waits for
   * a refetch between two gestures, so a base taken from the render that scheduled the command is
   * stale the moment the previous one lands, and a lone user in their own repository was told
   * "someone else changed this repository". Four commands with no pause between them.
   */
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(400)
  const spread = await page.locator('.ag-canvas').boundingBox()
  let slot = 0
  for (const label of ['Business Actor', 'Business Process', 'Business Object', 'Application Function']) {
    await dragFromPalette(page, label, { x: spread.x + 200 + slot * 240, y: spread.y + 620 })
    await page.keyboard.press('Escape')
    slot += 1
  }
  await page.waitForTimeout(3000)
  const conflict = await page.locator('.ag-banner-error').count()
  const banner = conflict ? await page.locator('.ag-banner-error').first().textContent() : ''
  check('four edits in a row do not collide with each other', conflict === 0, banner)
  await page.locator('.ag-splitter-left .ag-splitter-toggle').click()
  await page.waitForTimeout(400)

  // --- alignment guides: drag until it lines up, and it sticks ---
  // Ledger is dragged towards the axis the new element sits on. Within tolerance the guide shows
  // and the position snaps exactly, which is what stops a diagram drifting a pixel at a time.
  const target = await nodeAt(madeKey).locator('.ag-body').boundingBox()
  const ledgerBox = await nodeAt(ledgerKey).locator('.ag-body').boundingBox()
  await page.mouse.move(ledgerBox.x + ledgerBox.width / 2, ledgerBox.y + ledgerBox.height / 2)
  await page.mouse.down()
  // Four pixels off the target's left edge: inside the tolerance, so it should be pulled in.
  await page.mouse.move(target.x + ledgerBox.width / 2 + 4, ledgerBox.y + ledgerBox.height / 2 - 140, { steps: 14 })
  await page.waitForTimeout(250)
  check('a guide appears when edges line up', (await page.locator('.ag-guide').count()) > 0)
  await page.screenshot({ path: `${outDir}/35-guides.png` })
  await page.mouse.up()
  await page.waitForTimeout(1200)
  check('guides clear once the drag ends', (await page.locator('.ag-guide').count()) === 0)
  // Advice, not action: the guide says when an edge lines up and leaves the element alone. It
  // used to pull the box flush, which meant the editor spent the last few pixels arguing.
  const settled = await nodeAt(ledgerKey).locator('.ag-body').boundingBox()
  check(
    'the guide advises without moving the element',
    Math.abs(settled.x - (target.x + 5)) < 12,
    `left edge ${Math.round(settled.x)}, aimed at ${Math.round(target.x + 5)}`,
  )

  /*
   * --- the connection router is a property of the view ---
   *
   * Archi's two options and Archi's default: straight lines that take bendpoints, or orthogonal
   * runs that do not. Chosen per view and saved for everyone, because a colleague opening the
   * diagram should see it as it was drawn.
   */
  const openMenu = async () => {
    await page.getByRole('button', { name: 'More' }).click()
    await page.waitForTimeout(300)
  }
  await openMenu()
  // A real click inside the menu, because selectOption sets a value without one — which is how a
  // backdrop covering the whole popover went unnoticed: every check passed while every click in
  // the menu was landing on the thing that dismisses it.
  await page.locator('.ag-menu-popover label').click()
  check('the menu survives a click inside it', (await page.locator('.ag-menu-popover').count()) === 1)
  const routerSelect = page.locator('.ag-router-select')
  check('the view opens on straight lines, as Archi does', (await routerSelect.inputValue()) === 'straight')
  await routerSelect.selectOption('orthogonal')
  await page.keyboard.press('Escape')
  await page.waitForTimeout(2500)
  const bent = await page.evaluate(
    () =>
      [...document.querySelectorAll('.ag-paper .ag-link .ag-link-line')]
        .map((path) => ((path.getAttribute('d') ?? '').match(/L/g) ?? []).length)
        .filter((segments) => segments > 1).length,
  )
  check('orthogonal routing bends the diagonals into runs', bent > 0, `${bent} lines with corners`)
  await page.screenshot({ path: `${outDir}/36-orthogonal.png` })

  // Saved, not merely applied: reopening the view must show the same routing.
  await page.keyboard.press('Control+s')
  await page.waitForTimeout(2000)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await openRepository(page)
  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3000)
  await openMenu()
  check(
    'the choice survives a reload',
    (await page.locator('.ag-router-select').inputValue()) === 'orthogonal',
  )
  await page.keyboard.press('Escape')

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
  /*
   * Every element is a rectangle and the glyph says what it is, which is Archi's default figure.
   *
   * An earlier version varied the outline — a pill for services, rounded for behaviour. It reads
   * as a different *kind* of thing rather than a different type of the same thing, and Archi does
   * not do it. This check exists so the vocabulary does not creep back in.
   */
  const service = shapes.find((shape) => shape.name === 'Application Service')
  const component = shapes.find((shape) => shape.name === 'Payment System')
  check(
    'a service and a component are drawn as the same rectangle',
    service && component && service.radius <= 4 && component.radius <= 4,
    `service rx=${service?.radius}, component rx=${component?.radius}`,
  )
  await page.screenshot({ path: `${outDir}/34-notation.png` })

  /*
   * --- a view reopens exactly as it was left ---
   *
   * Reported from use: make changes, reopen the view, and the elements had been rearranged. Both
   * the effect that loads a view's layout and the one that places unplaced elements run in the
   * same commit, so the second saw the *previous* view's coordinates, called every element of the
   * new view unplaced, laid them on the grid and saved that over the real layout.
   */
  const geometryOf = () =>
    page.evaluate(() =>
      [...document.querySelectorAll('.ag-paper .ag-node')]
        .map((node) => {
          const body = node.querySelector('.ag-body')
          return `${node.getAttribute('data-key')}@${body.getAttribute('width')}x${body.getAttribute('height')}`
        })
        .sort()
        .join(' '),
    )
  const transformsOf = () =>
    page.evaluate(() =>
      [...document.querySelectorAll('.ag-paper .ag-node')]
        .map((node) => `${node.getAttribute('data-key')}${node.getAttribute('transform')}`)
        .sort()
        .join(' '),
    )

  const asLeft = { sizes: await geometryOf(), places: await transformsOf() }
  // Away to another view and back, which is what "reopened it" means.
  await page.locator('.ag-tree-item').first().click()
  await page.waitForTimeout(2500)
  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3500)
  const reopened = await transformsOf()
  check(
    'a view reopens with its elements where they were',
    reopened === asLeft.places,
    reopened === asLeft.places ? '' : `
      left: ${asLeft.places}
      back: ${reopened}`,
  )
  check('and at the sizes they were given', (await geometryOf()) === asLeft.sizes, 'sizes')
  await page.screenshot({ path: `${outDir}/37-reopened.png` })

  /*
   * --- saving is something the user does ---
   *
   * Layout used to be written a few hundred milliseconds after every drag: invisible, and the
   * origin of every ordering trap this editor has hit. It is now a draft in the browser until
   * Save, which is what makes it survivable and what makes it obvious whether it is safe.
   */
  // Start from saved rather than from whatever the earlier sections left behind: this is about
  // the cycle, not about which check happened to drag something last.
  await page.keyboard.press('Control+s')
  await page.waitForTimeout(2500)
  const savedLabel = await page.getByRole('button', { name: /^(Save|Saved)/ }).textContent()
  check('a saved view says so', savedLabel?.trim() === 'Saved', savedLabel ?? '')
  const moved = await nodeAt(ledgerKey).locator('.ag-body').boundingBox()
  await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2)
  await page.mouse.down()
  await page.mouse.move(moved.x + moved.width / 2, moved.y + moved.height / 2 + 160, { steps: 10 })
  await page.mouse.up()
  await page.waitForTimeout(600)
  check(
    'an edit marks the view unsaved rather than writing it',
    (await page.getByRole('button', { name: /^Save/ }).textContent())?.includes('*'),
  )

  // The draft outlives the page, which is the point of keeping it in the browser.
  const draftPlaces = await transformsOf()
  await page.reload({ waitUntil: 'domcontentloaded' })
  await openRepository(page)
  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3500)
  check('unsaved work survives a reload', (await transformsOf()) === draftPlaces, 'positions')
  check(
    'and is still marked unsaved',
    (await page.getByRole('button', { name: /^Save/ }).textContent())?.includes('*'),
  )

  await page.keyboard.press('Control+s')
  await page.waitForTimeout(2500)
  check(
    'Ctrl+S saves it',
    (await page.getByRole('button', { name: /^(Save|Saved)/ }).textContent())?.trim() === 'Saved',
  )
  await page.screenshot({ path: `${outDir}/39-saving.png` })

  /*
   * --- the small things that grate every day ---
   *
   * A palette that has gone monochrome, a notice that cannot be dismissed, a rename that commits
   * because you clicked elsewhere, a relationship you can read but not remove.
   */
  /*
   * --- filing something in a folder keeps it there ---
   *
   * Reported from use: elements would not move into folders. The assignment was stored and the
   * endpoint answered 204, but the elements listing never carried a folder field — so the tree
   * drew everything at the root and the move looked like it had not happened.
   */
  await page.getByRole('tab', { name: /^Repository/ }).click()
  await page.waitForTimeout(600)
  if ((await page.locator('.ag-tree-row.ag-tree-folder').count()) === 0) {
    page.once('dialog', (dialog) => dialog.accept(`filed ${process.pid}`))
    await page.locator('.ag-tree-toolbar button').click()
    await page.waitForTimeout(2000)
  }
  const firstLeaf = page.locator('.ag-tree-row.ag-tree-item').first()
  const filedKey = await firstLeaf.locator('.ag-tree-label').textContent()
  await firstLeaf.hover()
  const options = await firstLeaf.locator('.ag-tree-move option').count()
  if (options > 1) {
    await firstLeaf.locator('.ag-tree-move').selectOption({ index: 1 })
    await page.waitForTimeout(2500)
    const filedUnder = await page.evaluate((label) => {
      const rows = [...document.querySelectorAll('.ag-tree-row')]
      const row = rows.find((r) => r.querySelector('.ag-tree-label')?.textContent?.trim() === label)
      return row ? Number(row.querySelectorAll('.ag-tree-guide').length) : -1
    }, filedKey?.trim())
    check('an element filed in a folder is drawn inside it', filedUnder > 0, `depth ${filedUnder}`)
  }

  // The tree is a tree: rows walk with the arrow keys, as they do in a file browser.
  await page.getByRole('tab', { name: /^Views/ }).click()
  await page.waitForTimeout(400)
  await page.locator('.ag-tree-row').first().focus()
  await page.keyboard.press('ArrowDown')
  await page.waitForTimeout(200)
  const walked = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.ag-tree-row')]
    return rows.indexOf(document.activeElement)
  })
  check('the tree walks with the arrow keys', walked === 1, `focus on row ${walked}`)

  await page.getByRole('tab', { name: /^Palette/ }).click()
  await page.waitForTimeout(400)
  check(
    'the palette carries its layer colours',
    await page.evaluate(() => {
      const item = document.querySelector('.ag-palette-item')
      if (!item) return false
      const border = getComputedStyle(item).borderLeftColor
      return border !== '' && border !== 'rgba(0, 0, 0, 0)'
    }),
  )

  // Only if it is hidden: the toggle is a toggle, and closing it here is the opposite of the aim.
  if ((await page.locator('.ag-properties').count()) === 0) {
    await page.locator('.ag-splitter-right .ag-splitter-toggle').click()
    await page.waitForTimeout(400)
  }
  await clickNode(ledgerKey, 'centre')
  await page.waitForSelector('.ag-properties input', { timeout: 5000 })

  // Typing offers the choice; discarding puts it back rather than committing anything.
  const nameField = page.locator('.ag-properties .ag-field input').first()
  const originalName = await nameField.inputValue()
  await nameField.fill(`${originalName} edited`)
  await page.waitForTimeout(200)
  check('editing a name offers Save and Discard', (await page.locator('.ag-props-actions button').count()) === 2)
  await page.locator('.ag-props-actions button', { hasText: 'Discard' }).click()
  await page.waitForTimeout(500)
  check('Discard puts the name back', (await nameField.inputValue()) === originalName, originalName)

  // A relationship can be removed from where it is read.
  const listed = await page.locator('.ag-props-usage li').count()
  if (listed > 0) {
    page.once('dialog', (dialog) => dialog.accept())
    await page.locator('.ag-props-usage .ag-props-remove').first().click()
    await page.waitForTimeout(3000)
    check(
      'a relationship can be deleted from the properties panel',
      (await page.locator('.ag-props-usage li').count()) === listed - 1,
      `${listed} -> ${await page.locator('.ag-props-usage li').count()}`,
    )
  }

  // And a notice can be got rid of.
  await page.keyboard.press('Control+s')
  await page.waitForTimeout(1500)
  if (await page.locator('.ag-banner').count()) {
    await page.locator('.ag-banner-close').first().click()
    await page.waitForTimeout(300)
    check('a notice can be dismissed', (await page.locator('.ag-banner').count()) === 0)
  }

  /*
   * --- which box is on top ---
   *
   * Overlaps are unavoidable once a diagram has containers and frames, and until now the order
   * they resolved in was the order the elements happened to reach the graph. Stacking is
   * presentation: it rides with the coordinates and is kept by Save (ADR-0003).
   */
  const stackOf = () =>
    page.evaluate(() => [...document.querySelectorAll('.ag-paper .ag-node')].map((n) => n.getAttribute('data-key')))

  const restack = async (key, action) => {
    const box = await nodeAt(key).locator('.ag-body').boundingBox()
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2, { button: 'right' })
    await page.waitForSelector('.ag-context', { timeout: 5000 })
    await page.locator('.ag-context-row button', { hasText: action }).click()
    await page.waitForTimeout(800)
  }

  await restack(ledgerKey, 'Send to back')
  const sentBack = await stackOf()
  check('"Send to back" puts an element under the others', sentBack.indexOf(ledgerKey) === 0, sentBack.join(' '))

  await restack(ledgerKey, 'Bring to front')
  const brought = await stackOf()
  check(
    '"Bring to front" puts it over them again',
    brought.indexOf(ledgerKey) === brought.length - 1,
    brought.join(' '),
  )

  // Sending a *child* back must not file it under its own container, where an opaque box would
  // hide it and the element would look deleted.
  await restack(api, 'Send to back')
  const nested = await stackOf()
  check(
    'a nested element sent back still sits over its container',
    nested.indexOf(api) > nested.indexOf(system),
    nested.join(' '),
  )

  await page.keyboard.press('Control+s')
  await page.waitForTimeout(2500)
  await restack(ledgerKey, 'Send to back')
  await page.keyboard.press('Control+s')
  await page.waitForTimeout(2500)
  await page.reload({ waitUntil: 'domcontentloaded' })
  await openRepository(page)
  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3500)
  const reloaded = await stackOf()
  check('the stacking order is kept by Save', reloaded.indexOf(ledgerKey) === 0, reloaded.join(' '))
  await page.screenshot({ path: `${outDir}/40-stacking.png` })

  /*
   * --- a view may leave a line out ---
   *
   * A view draws the relationships whose endpoints are both on it, and until now the only way to
   * be rid of one was to delete it from the model — a diagram-level wish answered with a
   * repository-level change. Hiding is presentation (ADR-0011); the model keeps asserting it.
   */
  // A point on the line itself: a diagonal's bounding-box centre is empty space, so clicking the
  // element's box would miss the stroke and land on the canvas.
  const pointOnLink = () =>
    page.evaluate(() => {
      const path = document.querySelector('.ag-paper .ag-link:not(.ag-link-derived) .ag-link-line')
      if (!path) return null
      const middle = path.getPointAtLength(path.getTotalLength() / 2)
      const at = new DOMPoint(middle.x, middle.y).matrixTransform(path.getScreenCTM())
      return { x: at.x, y: at.y, key: path.closest('.ag-link').getAttribute('data-key') }
    })

  const linkAt = await pointOnLink()
  if (linkAt) {
    const linesBefore = await page.locator('.ag-paper .ag-link').count()

    await page.mouse.click(linkAt.x, linkAt.y, { button: 'right' })
    await page.waitForSelector('.ag-context-link', { timeout: 5000 })
    const linkActions = await page.evaluate(() =>
      [...document.querySelectorAll('.ag-context-link .ag-context-actions > button')].map((b) =>
        b.textContent.replace(/\s+/g, ' ').trim(),
      ),
    )
    check(
      'a line offers hiding and deleting as different things',
      linkActions.length === 2 && linkActions[0].startsWith('Hide on this view'),
      linkActions.join(' | '),
    )
    await page.screenshot({ path: `${outDir}/41-link-menu.png` })

    await page.locator('.ag-context-link button', { hasText: 'Hide on this view' }).click()
    await page.waitForTimeout(800)
    check(
      'hiding takes the line off the view',
      (await page.locator('.ag-paper .ag-link').count()) === linesBefore - 1,
      `${linesBefore} -> ${await page.locator('.ag-paper .ag-link').count()}`,
    )
    check('and the view admits it is holding something back', (await page.locator('.ag-hidden-count').count()) === 1)

    await page.keyboard.press('Control+s')
    await page.waitForTimeout(2500)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await openRepository(page)
    await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
    await page.waitForTimeout(3500)
    check(
      'the view still hides it after a reload',
      (await page.locator('.ag-hidden-count').textContent())?.includes('1'),
      (await page.locator('.ag-hidden-count').textContent()) ?? 'no counter',
    )

    // And what was hidden can be found and put back.
    await page.locator('.ag-hidden-count').click()
    await page.waitForTimeout(400)
    check('the hidden lines can be read', (await page.locator('.ag-hidden-list li').count()) === 1)
    // Named endpoints, not a bare key: the row is drawn from the relationship the model still
    // holds, so seeing it here is the evidence that hiding did not delete anything.
    const row = (await page.locator('.ag-hidden-list li span').first().textContent())?.trim() ?? ''
    check('and the model still holds what the view hides', row.includes('→') && !row.includes(linkAt.key), row)
    await page.screenshot({ path: `${outDir}/42-hidden-list.png` })
    await page.locator('.ag-hidden-list li .ag-link').first().click()
    await page.waitForTimeout(800)
    check(
      'showing it again draws it',
      (await page.locator('.ag-paper .ag-link').count()) === linesBefore &&
        (await page.locator('.ag-hidden-count').count()) === 0,
      `${await page.locator('.ag-paper .ag-link').count()} lines`,
    )
    await page.keyboard.press('Control+s')
    await page.waitForTimeout(2000)
  } else {
    check('a line offers hiding and deleting as different things', false, 'no line on the view to try it on')
  }

  /*
   * --- a band selects, Ctrl+D duplicates, Ctrl+V shows the same elements elsewhere ---
   *
   * Three gestures every canvas has, and the two pastes mean genuinely different things here:
   * showing an element on a second view is membership, making another one is a new element in the
   * repository (ADR-0009).
   */
  const bandOver = async (keys) => {
    const boxes = await Promise.all(keys.map((key) => nodeAt(key).locator('.ag-body').boundingBox()))
    const left = Math.min(...boxes.map((b) => b.x)) - 30
    const top = Math.min(...boxes.map((b) => b.y)) - 30
    const right = Math.max(...boxes.map((b) => b.x + b.width)) + 30
    const bottom = Math.max(...boxes.map((b) => b.y + b.height)) + 30
    await page.mouse.move(left, top)
    await page.mouse.down()
    await page.mouse.move(right, bottom, { steps: 12 })
    await page.mouse.up()
    await page.waitForTimeout(500)
  }

  const serviceKey = await keyOf('Application Service')
  // Knocked out of line first: these two were created one under the other, so aligning them
  // would have passed without moving anything.
  const nudged = await nodeAt(serviceKey).locator('.ag-body').boundingBox()
  await page.mouse.move(nudged.x + nudged.width / 2, nudged.y + nudged.height / 2)
  await page.mouse.down()
  await page.mouse.move(nudged.x + nudged.width / 2 + 90, nudged.y + nudged.height / 2, { steps: 10 })
  await page.mouse.up()
  await page.waitForTimeout(600)
  await bandOver([ledgerKey, serviceKey])
  check(
    'a band across the canvas selects what it encloses',
    (await page.locator('.ag-paper .ag-node-selected').count()) === 2,
    `${await page.locator('.ag-paper .ag-node-selected').count()} selected`,
  )
  await page.screenshot({ path: `${outDir}/43-marquee.png` })

  /*
   * --- align brings a selection into a line ---
   *
   * The guides advise while one element is dragged; bringing six boxes into a column is a
   * different act, and the band above is what makes asking for it possible.
   */
  const boxOf = async (key) => nodeAt(key).locator('.ag-body').boundingBox()
  const apart = Math.abs((await boxOf(ledgerKey)).x - (await boxOf(serviceKey)).x)
  const rightClicked = await boxOf(ledgerKey)
  await page.mouse.click(
    rightClicked.x + rightClicked.width / 2,
    rightClicked.y + rightClicked.height / 2,
    { button: 'right' },
  )
  await page.waitForSelector('.ag-context-align', { timeout: 5000 })
  await page.locator('.ag-context-align button', { hasText: 'Left' }).click()
  await page.waitForTimeout(1200)
  const aligned = Math.abs((await boxOf(ledgerKey)).x - (await boxOf(serviceKey)).x)
  check(
    'aligning left puts the selection on one edge',
    apart > 20 && aligned < 1.5,
    `${apart.toFixed(0)}px apart -> ${aligned.toFixed(1)}px`,
  )

  // A move made above the canvas is still a move: one press of undo, not none.
  await page.keyboard.press('Control+z')
  await page.waitForTimeout(1200)
  check(
    'and one undo puts them back',
    Math.abs(Math.abs((await boxOf(ledgerKey)).x - (await boxOf(serviceKey)).x) - apart) < 1.5,
    `${Math.abs((await boxOf(ledgerKey)).x - (await boxOf(serviceKey)).x).toFixed(0)}px apart`,
  )
  await page.keyboard.press('Control+Shift+z')
  await page.waitForTimeout(1000)

  // The daily gesture, through the same route: drag a box, press undo, expect the box back.
  const dragged = await boxOf(ledgerKey)
  await page.mouse.move(dragged.x + dragged.width / 2, dragged.y + dragged.height / 2)
  await page.mouse.down()
  await page.mouse.move(dragged.x + dragged.width / 2 - 120, dragged.y + dragged.height / 2, { steps: 10 })
  await page.mouse.up()
  await page.waitForTimeout(600)
  await page.keyboard.press('Control+z')
  await page.waitForTimeout(1200)
  check(
    'undoing a move puts the box back where it was',
    Math.abs((await boxOf(ledgerKey)).x - dragged.x) < 1.5,
    `${dragged.x.toFixed(0)} -> ${(await boxOf(ledgerKey)).x.toFixed(0)}`,
  )

  const beforeDuplicate = await page.locator('.ag-paper .ag-node').count()
  await page.keyboard.press('Control+d')
  await page.waitForTimeout(3000)
  check(
    'Ctrl+D makes another element like the one selected',
    (await page.locator('.ag-paper .ag-node').count()) === beforeDuplicate + 2,
    `${beforeDuplicate} -> ${await page.locator('.ag-paper .ag-node').count()}`,
  )
  check(
    'and says it is a copy rather than reusing the name',
    (await page.locator('.ag-paper .ag-node', { hasText: '(copy)' }).count()) === 2,
  )

  // Copying puts the same elements on a second view — no new elements in the repository.
  // By the corner: the duplicate sits a step off the original and covers its middle.
  await clickNode(ledgerKey)
  await page.waitForTimeout(300)
  check(
    'the original is the one selected when clicked beside its copy',
    (await page.locator('.ag-paper .ag-node-selected').getAttribute('data-key')) === ledgerKey,
    (await page.locator('.ag-paper .ag-node-selected').first().getAttribute('data-key')) ?? 'none',
  )
  await page.keyboard.press('Control+c')
  await page.waitForTimeout(400)
  const repositorySize = async () =>
    Number((await page.locator('.ag-toolbar .ag-muted').last().textContent()).match(/· (\d+) in repository/)[1])
  const beforePaste = await repositorySize()

  const secondView = `${viewName} b`
  page.once('dialog', (dialog) => dialog.accept(secondView))
  await page.locator('.ag-block-button').first().click()
  await page.waitForTimeout(3000)
  await page.keyboard.press('Control+v')
  await page.waitForTimeout(3000)
  check(
    'Ctrl+V shows the copied element on another view',
    (await page.locator('.ag-paper .ag-node').count()) === 1,
    `${await page.locator('.ag-paper .ag-node').count()} on the new view`,
  )
  check(
    'and does not make a second element to do it',
    (await repositorySize()) === beforePaste,
    `${beforePaste} -> ${await repositorySize()}`,
  )
  await page.screenshot({ path: `${outDir}/44-paste.png` })

  /*
   * --- the diagram as a file ---
   *
   * A view has to be able to leave the product: every document that embeds architecture embeds a
   * picture of it. The export is the SVG on screen minus the editor's furniture, carrying the
   * stylesheet, because nothing that opens the file will consult ours.
   */
  /*
   * --- an element knows every diagram it is on ---
   *
   * The claim a diagramming tool cannot make: the element belongs to the repository and each view
   * merely shows it, so "where is this used" has to answer with views, not only with lines. It is
   * also what turns "delete from the model" from a leap into a decision.
   */
  await clickNode(await keyOf('Ledger'), 'centre')
  await page.waitForSelector('.ag-props-group h3', { timeout: 5000 })
  await page.waitForFunction(
    () => [...document.querySelectorAll('.ag-props-group h3')].some((h) => /On \d+ views?/.test(h.textContent)),
    { timeout: 5000 },
  )
  const onViews = await page.evaluate(() => {
    const heads = [...document.querySelectorAll('.ag-props-group h3')]
    const head = heads.find((h) => /On \d+ views?/.test(h.textContent))
    return {
      heading: head.textContent,
      names: [...head.parentElement.querySelectorAll('li .ag-link')].map((b) => b.textContent.trim()),
    }
  })
  check(
    'the panel names every view the element is on',
    onViews.heading === 'On 2 views' && onViews.names.includes(viewName),
    `${onViews.heading}: ${onViews.names.join(', ')}`,
  )
  // And they are a way in, not only a fact: this is the walk from one diagram to another.
  await page.locator('.ag-props-group li .ag-link', { hasText: viewName }).first().click()
  await page.waitForTimeout(3000)
  check(
    'and opens the one you click',
    (await page.locator('.ag-toolbar strong, .ag-view-name').first().textContent())?.includes(viewName),
    (await page.locator('.ag-toolbar strong, .ag-view-name').first().textContent()) ?? '',
  )
  await page.screenshot({ path: `${outDir}/45-where-used.png` })

  /*
   * --- what a container's parts do, the container is understood to do ---
   *
   * The case context views exist for, and the one that quietly broke: containment became a
   * relationship on the canvas (ADR-0011) while derivation went on reading the old field, so a
   * nested element's dependencies stopped lifting to the box that holds it. Nest, take the child
   * off the view, and the line has to reappear at the level above — derived, and saying so.
   */
  const holder = await nodeAt(system).locator('.ag-body').boundingBox()
  const held = await nodeAt(ledgerKey).locator('.ag-body').boundingBox()
  await page.mouse.move(held.x + held.width / 2, held.y + 12)
  await page.mouse.down()
  // Into the empty strip under the container's own name: dropping lower would land on one of the
  // elements already inside it, and nest into that instead.
  await page.mouse.move(holder.x + 50, holder.y + 30, { steps: 14 })
  await page.mouse.up()
  await page.waitForTimeout(1500)
  check('nesting still asks what it means', (await page.locator('.ag-picker').count()) > 0)
  await page.locator('.ag-picker button', { hasText: 'Composition' }).first().click()
  await page.waitForTimeout(3000)

  await clickNode(ledgerKey)
  await page.keyboard.press('Delete')
  await page.waitForTimeout(3000)
  const derived = await page.evaluate(() =>
    [...document.querySelectorAll('.ag-paper .ag-link-derived')].map(
      (link) => link.querySelector('.ag-link-label')?.textContent ?? '',
    ),
  )
  check(
    'a dependency of a nested element lifts to its container',
    derived.some((label) => label.includes('Serving')),
    derived.join(' | ') || 'no derived lines',
  )
  await page.screenshot({ path: `${outDir}/46-derived.png` })

  /*
   * --- a view can be narrowed without losing anything ---
   *
   * A filter is a condition on what the view draws, never on what belongs to it (ADR-0003), and it
   * is applied on the server so that the lines lift through what is no longer drawn. Which is why
   * clearing it puts every box back exactly where it was.
   */
  const boxesOf = () =>
    page.evaluate(() =>
      Object.fromEntries(
        [...document.querySelectorAll('.ag-paper .ag-node')].map((node) => [
          node.getAttribute('data-key'),
          node.getAttribute('transform'),
        ]),
      ),
    )
  const unfiltered = await boxesOf()
  await openMenu()
  await page.locator('.ag-filter-layers button', { hasText: 'business' }).click()
  await page.waitForTimeout(3000)
  const narrowed = await boxesOf()
  check(
    'filtering to a layer draws only that layer',
    Object.keys(narrowed).length > 0 && Object.keys(narrowed).length < Object.keys(unfiltered).length,
    `${Object.keys(unfiltered).length} -> ${Object.keys(narrowed).length}`,
  )
  check(
    'and says what it is leaving out',
    (await page.locator('.ag-filter-foot').textContent())?.includes('left out'),
    (await page.locator('.ag-filter-foot').textContent())?.slice(0, 80) ?? 'no note',
  )
  await page.screenshot({ path: `${outDir}/49-filtered.png` })

  await page.locator('.ag-filter-foot .ag-link').click()
  await page.waitForTimeout(3000)
  const restored = await boxesOf()
  check(
    'clearing it brings every element back where it was',
    JSON.stringify(restored) === JSON.stringify(unfiltered),
    `${Object.keys(restored).length} of ${Object.keys(unfiltered).length}`,
  )
  await page.keyboard.press('Escape')

  /*
   * --- the same graph, read as C4 ---
   *
   * The product's second claim after "the repository is the source of truth": one graph, several
   * notations. Nothing is stored for it — whether Payment System is a system or a container is
   * depth relative to the focus (ADR-0007) — and what C4 cannot draw is named rather than dropped.
   */
  const c4From = async (key, level) => {
    const box = await nodeAt(key).locator('.ag-body').boundingBox()
    await page.mouse.click(box.x + box.width / 2, box.y + 14, { button: 'right' })
    await page.waitForSelector('.ag-context-c4', { timeout: 5000 })
    await page.locator('.ag-context-c4 button', { hasText: level }).click()
    await page.waitForTimeout(4000)
  }

  await c4From(system, 'System Context')
  const contextView = await page.locator('.ag-view-name').textContent()
  check('a C4 context view is created from an element', contextView?.includes('System Context'), contextView ?? '')
  const contextKeys = await page.evaluate(() =>
    [...document.querySelectorAll('.ag-paper .ag-node')].map((node) => node.getAttribute('data-key')),
  )
  check(
    'and shows the system without its insides',
    contextKeys.includes(system) && !contextKeys.includes(api),
    contextKeys.join(' '),
  )
  check(
    'and says what C4 has no form for',
    (await page.locator('.ag-banner').textContent())?.includes('no form in C4'),
    (await page.locator('.ag-banner').textContent())?.slice(0, 120) ?? 'no banner',
  )
  await page.screenshot({ path: `${outDir}/47-c4-context.png` })

  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3000)
  await c4From(system, 'Container')
  const containerKeys = await page.evaluate(() =>
    [...document.querySelectorAll('.ag-paper .ag-node')].map((node) => node.getAttribute('data-key')),
  )
  check(
    'a container view opens the system one level down',
    containerKeys.includes(system) && containerKeys.includes(api),
    containerKeys.join(' '),
  )
  const insideBoundary = await page.evaluate((keys) => {
    const box = (key) => {
      const body = document.querySelector(`.ag-paper .ag-node[data-key="${key}"] .ag-body`)
      return body ? body.getBoundingClientRect() : null
    }
    const outer = box(keys.system)
    const inner = box(keys.api)
    if (!outer || !inner) return false
    return inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom
  }, { system, api })
  check('and draws the container inside the boundary, as C4 does', insideBoundary)
  await page.screenshot({ path: `${outDir}/48-c4-container.png` })

  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3000)

  // Back to the view with containers and lines on it: exporting one lone box proves very little.
  await page.locator('.ag-tree-item', { hasText: viewName }).first().click()
  await page.waitForTimeout(3000)
  for (const format of ['svg', 'png']) {
    await openMenu()
    const downloading = page.waitForEvent('download', { timeout: 15000 })
    await page.locator('.ag-menu-popover button', { hasText: `Export ${format.toUpperCase()}` }).click()
    const file = await downloading
    const saved = `${outDir}/export.${format}`
    await file.saveAs(saved)
    const size = statSync(saved).size
    if (format === 'svg') {
      const text = readFileSync(saved, 'utf8')
      check(
        'exported SVG carries the diagram and its styles',
        text.includes('<svg') && text.includes('ag-node') && text.includes('.ag-body'),
        `${size} bytes`,
      )
      check(
        'and none of the editor furniture',
        !text.includes('ag-marquee') || !text.includes('joint-tools-layer'),
      )
    } else {
      const magic = readFileSync(saved).subarray(0, 8).toString('hex')
      check('exported PNG is a PNG', magic === '89504e470d0a1a0a' && size > 1000, `${magic}, ${size} bytes`)
    }
  }

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
