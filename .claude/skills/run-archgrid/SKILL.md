---
name: run-archgrid
description: Build, launch, drive and screenshot the ArchGrid app (server + web editor) for UI verification. Use when asked to run, start, open, test in the browser, screenshot, or visually confirm a change to the editor canvas, palette, notation, minimap or context menu.
---

# Running ArchGrid

Three processes: PostgreSQL and Keycloak in Docker, the Spring Boot API on 8080, the Vite dev
server on 5173. The web client authenticates through Keycloak, so there is no way to skip the
identity provider — the driver fills its login form like a person would.

**Drive it with `.claude/skills/run-archgrid/driver.mjs`.** Do not stop at "the dev server
started". Type-checking and the 55 server tests are blind to the defects that matter here:
arrowheads that never render, an empty minimap, a menu falling off the window edge. All three
shipped and were found only by opening the app.

All paths are relative to the repository root. Commands are PowerShell on Windows.

## Prerequisites

Installed once. Chromium lands under `web/node_modules`, not in the user profile:

```powershell
cd web
npm install
$env:PLAYWRIGHT_BROWSERS_PATH = "0"
npx playwright install chromium
```

Docker Desktop must be running. It does not survive a reboot, and its daemon takes about a
minute to accept connections:

```powershell
if (-not (Get-Process "Docker Desktop" -ErrorAction SilentlyContinue)) {
  Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
}
```

## Bring the stack up

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

Wait for PostgreSQL to report healthy before starting the API — Flyway fails the boot if it
cannot connect:

```powershell
while ((docker inspect --format "{{.State.Health.Status}}" archgrid-postgres) -ne "healthy") { Start-Sleep -Seconds 3 }
```

Start the API and the web dev server in the background:

```powershell
$log = "$env:TEMP\archgrid"
New-Item -ItemType Directory -Force -Path $log | Out-Null
Start-Process cmd.exe -ArgumentList "/c", "cd /d $PWD\server && .\gradlew.bat bootRun --console=plain > $log\boot.log 2>&1" -WindowStyle Hidden
Start-Process cmd.exe -ArgumentList "/c", "cd /d $PWD\web && npm run dev > $log\vite.log 2>&1" -WindowStyle Hidden
```

Wait for both:

```powershell
$deadline = (Get-Date).AddMinutes(4)
while ((Get-Date) -lt $deadline) {
  $api = try { (Invoke-RestMethod "http://localhost:8080/actuator/health" -TimeoutSec 3).status -eq 'UP' } catch { $false }
  $web = try { (Invoke-WebRequest "http://localhost:5173" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { $false }
  if ($api -and $web) { break }
  Start-Sleep -Seconds 4
}
"api=$api web=$web"
```

## Seed a workspace (first run only)

The workspace API is read-only, so the first row goes in by hand. Without it the app shows
"No repositories" and the driver stops with instructions:

```powershell
docker exec archgrid-postgres psql -U archgrid -d archgrid -c "insert into workspace (id, ""key"", name) values (gen_random_uuid(), 'default', 'Default workspace') on conflict (""key"") do nothing"
```

## Run: the driver (agent path)

```powershell
$env:PLAYWRIGHT_BROWSERS_PATH = "0"
node .claude/skills/run-archgrid/driver.mjs smoke
```

Signs in as `architect`/`architect`, opens the workspace's graph, and checks: nodes render, every edge carries an arrowhead, every marker reference
resolves, the minimap draws one rectangle per node, nothing sits outside the viewport after
`fitView`, the right-click menu offers both delete modes and stays on screen, Ctrl+Z and
Ctrl+Shift+Z work, and Delete removes from the view while offering the element back.

Verified output:

```
[archgrid] preflight ok: web, api and idp all answering
[archgrid] signed in as architect
[archgrid] OK   canvas renders nodes — 3 nodes, 0 edges
[archgrid] SKIP notation checks - the repository has no relationships
[archgrid] OK   minimap draws nodes — 3 of 3
[archgrid] OK   all nodes are inside the viewport after fitView — 0 off-screen
[archgrid] OK   no two nodes overlap — 0 overlapping pairs
[archgrid] OK   context menu offers both delete modes
[archgrid] OK   context menu stays inside the window
[archgrid] OK   Ctrl+Z undoes a creation — 3 → 4 → 3
[archgrid] OK   Ctrl+Shift+Z redoes it — 3 → 4
[archgrid] OK   Delete removes from the view and offers it back — 2 restorable
[archgrid] OK   renaming from the properties panel reaches the canvas — Renamed 5413
[archgrid] OK   explorer lists every element in the repository — 4 of 4
[archgrid] OK   elements off the view offer "add to view" — 1 addable
[archgrid] OK   explorer search narrows the list — "Renamed 5413" matched 1 of 4
[archgrid] --- 13/13 checks passed, screenshots in build/ui-check/
```

The notation checks are skipped on an empty repository, which is a fresh database rather than a
fault. Build something with relationships to exercise them.

Screenshots land in `build/ui-check/`. **Open them.** Green checks say the DOM has the right
attributes; only the image says the diagram reads correctly. The double-wrapped marker bug
passed every attribute check that existed at the time.

## Run: the canvas gestures

`smoke` asserts on whatever view it finds. The gestures need known geometry, so they get their
own mode and their own freshly created view:

```powershell
node .claude/skills/run-archgrid/driver.mjs interactions
```

Resizing an element and reloading to prove the size stuck, collapsing and dragging both side
panels, dropping one element into another to nest it, checking the container frame actually
encloses its child, and drawing a relationship by releasing over the middle of the target rather
than on its handle. Verified output:

```
[archgrid] OK   a node offers a connection point on every side — 4 handles
[archgrid] OK   dragging a corner resizes the element — flow width 251
[archgrid] OK   the size is persisted — 251 -> 251
[archgrid] OK   the left panel collapses
[archgrid] OK   the canvas takes the space the panel gave up — x=6 w=1300
[archgrid] OK   the left panel resizes by dragging — 240 -> 390
[archgrid] OK   the right panel collapses
[archgrid] OK   dropping one element on another nests it
[archgrid] OK   the container frame encloses its child — child 360x160 in frame 502x299
[archgrid] OK   a new element avoids the container it would otherwise cover
[archgrid] OK   the picker takes focus so a type can be chosen by keyboard — focus on BUTTON
[archgrid] OK   the relationship is drawn — 0 -> 1
[archgrid] --- 13/13 checks passed, screenshots in build/ui-check/
```

Each line there stands for a defect that shipped: a panel that collapsed but left its grid track
behind and pushed the canvas off screen, a size that saved and was immediately overwritten by the
refetch, a new element dropped on top of a container's connection points.

Other commands:

```powershell
node .claude/skills/run-archgrid/driver.mjs smoke --headed          # watch it happen
node .claude/skills/run-archgrid/driver.mjs smoke --workspace acme    # a specific repository
node .claude/skills/run-archgrid/driver.mjs shot --name palette     # one screenshot, no checks
```

Overridable via environment: `ARCHGRID_WEB`, `ARCHGRID_API`, `ARCHGRID_IDP`, `ARCHGRID_USER`,
`ARCHGRID_PASSWORD`.

## Run: a person at a browser (human path)

Same stack, then open `http://localhost:5173` and sign in as `architect`/`architect`
(owner + contributor) or `reader`/`reader` (read-only — useful for checking that mutations
are refused).

## Exercising the API directly

Faster than the UI when the question is about the model rather than the canvas:

```powershell
$d = Invoke-RestMethod "http://localhost:8081/realms/archgrid/.well-known/openid-configuration"
$t = Invoke-RestMethod -Method Post -Uri $d.token_endpoint -ContentType 'application/x-www-form-urlencoded' -Body @{ grant_type='password'; client_id='archgrid-web'; username='architect'; password='architect'; scope='openid' }
$H = @{ Authorization = "Bearer " + $t.access_token; "Content-Type" = "application/json" }
Invoke-RestMethod "http://localhost:8080/api/workspaces/default" -Headers $H
```

## Shut down

`Ctrl-C` does not reach a backgrounded process, so stop them by port:

```powershell
foreach ($port in @(8080, 5173)) {
  $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c[0].OwningProcess -Force }
}
docker compose -f deploy/docker-compose.yml down
```

## Gotchas

- **`PLAYWRIGHT_BROWSERS_PATH=0` is needed at run time, not just at install.** Set it only
  during install and Playwright looks in `%LOCALAPPDATA%\ms-playwright`, finds nothing, and
  tells you to run `npx playwright install` — which you already did.
- **PostgreSQL is published on 5433, not 5432.** A developer machine usually already has
  something on the default port; ours refused to start until the ports were separated.
- **PostgreSQL 18 images mount `/var/lib/postgresql`, not `/var/lib/postgresql/data`.** The
  old path puts the container in a restart loop with a long explanatory log nobody reads.
- **Auto-layout is asynchronous.** ELK resolves after the canvas mounts, then the view refits.
  Assert sooner than ~3 s and node counts are right while every position is still `{0,0}`.
- **React Flow wraps marker ids in `url(#…)` itself.** Passing `url(#id)` yields
  `url(#url(#id))`: the reference resolves to nothing, no arrowhead draws, and the line still
  renders — so the diagram looks merely plain rather than broken.
- **The minimap needs explicit `width`/`height` on nodes.** With CSS-only sizing it renders an
  empty rectangle.
- **Measure node size in flow units, not screen pixels.** `fitView` picks a zoom to suit the
  content, so the same node measures differently after a reload. Read `style.width` off
  `.react-flow__node`, and address the node by `data-id` — DOM order is parents-before-children
  and does not survive a re-render.
- **A node's `data-id` comes from its type, not its name.** An element created from the palette
  gets a key minted from the type ("application-component-7") and renaming never changes it.
  Locate nodes in tests by their visible label.
- **React Flow renders nodes as a flat list.** A nested child is a DOM *sibling* of its
  container, not a descendant, so `.container .child` selectors find nothing.
- **A container legitimately overlaps its children.** Any "no two nodes overlap" assertion has to
  exempt a box that fully encloses another.
- **`deleteKeyCode={null}` is deliberate.** React Flow's built-in delete would remove nodes
  without going through the command pipeline, leaving the canvas disagreeing with the model.
- **Use `.\gradlew.bat`, not `gradlew.bat`.** PowerShell does not resolve the current directory.
- **Model creation needs `ROLE_OWNER`.** `architect` has it; `reader` gets 403 by design.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `browserType.launch: Executable doesn't exist at …ms-playwright…` | `PLAYWRIGHT_BROWSERS_PATH=0` not set for this shell. Set it and re-run. |
| `error during connect: … dockerDesktopLinuxEngine` | Docker Desktop is not running. Start it and wait ~1 min. |
| `Bind for 0.0.0.0:5433 failed: port is already allocated` | Another container holds it. `docker ps` to find it, or set `ARCHGRID_DB_PORT`. |
| Driver exits 2 with "no workspace exists" | Run the seeding statement above. |
| Flyway fails with `Cannot enforce one graph per workspace` | The database predates ADR-0008 and holds several models in one workspace. Development instance: drop the volume (`docker compose ... down -v`). |
| Driver exits 2 at preflight | One of the three processes is not up. Check `$env:TEMP\archgrid\boot.log` and `vite.log`. |
| App loads but every mutation returns 403 | Role mapping. Realm roles are lower case and are upper-cased into authorities; check `RealmRoleGrantedAuthoritiesConverter` and its tests. |
| Keycloak login form never appears | Realm not imported. `docker compose … down` then `up -d` re-runs `--import-realm`. |
