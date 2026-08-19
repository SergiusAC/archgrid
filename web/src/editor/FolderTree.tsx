import { useCallback, useMemo, useRef, useState } from 'react'

import type { Folder } from '../api/types.ts'

export interface TreeItem {
  key: string
  label: string
  meta?: string
  folder: string | null
}

interface FolderTreeProps {
  folders: Folder[]
  items: TreeItem[]
  selected: string[]
  emptyLabel: string
  onSelect: (key: string) => void
  onCreateFolder: (parent: string | null) => void
  onDeleteFolder: (id: string) => void
  onMoveItem: (key: string, folder: string | null) => void
  renderActions?: (item: TreeItem) => React.ReactNode
}

/** One line of the tree, folder or leaf, already flattened in the order it is drawn. */
type Row =
  | { kind: 'folder'; id: string; label: string; depth: number; expanded: boolean; empty: boolean }
  | { kind: 'item'; id: string; depth: number; item: TreeItem }

const FOLDER_ICON = (
  <svg className="ag-tree-icon" viewBox="0 0 16 16" aria-hidden="true">
    <path d="M1.5 3.5h4l1.5 2h7.5v7h-13z" />
  </svg>
)

const LEAF_ICON = (
  <svg className="ag-tree-icon" viewBox="0 0 16 16" aria-hidden="true">
    <path d="M3.5 1.5h6l3 3v10h-9z" />
    <path d="M9.5 1.5v3h3" />
  </svg>
)

/**
 * Nested folders with their contents, drawn the way a file tree is drawn.
 *
 * Folders organise and nothing more — they mean nothing to the metamodel, do not affect
 * validation or C4 depth, and are not versioned (ADR-0009). That is why moving something between
 * them is a plain select, not a command, and why deleting one keeps its contents.
 *
 * Rendered as one flat list of rows rather than nested lists. Every row is then the same shape —
 * guides, a twisty, an icon, a label — which is what makes a tree scannable, and it puts keyboard
 * navigation within reach: moving between rows is moving along an array, not walking a structure.
 */
export default function FolderTree({
  folders,
  items,
  selected,
  emptyLabel,
  onSelect,
  onCreateFolder,
  onDeleteFolder,
  onMoveItem,
  renderActions,
}: FolderTreeProps) {
  const [collapsed, setCollapsed] = useState<Record<string, true>>({})
  const [focused, setFocused] = useState(0)
  const list = useRef<HTMLUListElement>(null)

  const childFolders = useMemo(() => {
    const byParent = new Map<string | null, Folder[]>()
    for (const folder of folders) {
      const bucket = byParent.get(folder.parent)
      if (bucket) {
        bucket.push(folder)
      } else {
        byParent.set(folder.parent, [folder])
      }
    }
    for (const bucket of byParent.values()) {
      bucket.sort((a, b) => a.name.localeCompare(b.name))
    }
    return byParent
  }, [folders])

  const itemsInFolder = useMemo(() => {
    const byFolder = new Map<string | null, TreeItem[]>()
    for (const item of items) {
      // An item whose folder was deleted underneath us falls back to the root rather than
      // vanishing from a tree that is meant to show everything.
      const folder = item.folder && folders.some((f) => f.id === item.folder) ? item.folder : null
      const bucket = byFolder.get(folder)
      if (bucket) {
        bucket.push(item)
      } else {
        byFolder.set(folder, [item])
      }
    }
    for (const bucket of byFolder.values()) {
      bucket.sort((a, b) => a.label.localeCompare(b.label))
    }
    return byFolder
  }, [items, folders])

  const rows = useMemo(() => {
    const flat: Row[] = []
    const walk = (parent: string | null, depth: number) => {
      for (const folder of childFolders.get(parent) ?? []) {
        const children = childFolders.get(folder.id) ?? []
        const contents = itemsInFolder.get(folder.id) ?? []
        const expanded = !collapsed[folder.id]
        flat.push({
          kind: 'folder',
          id: folder.id,
          label: folder.name,
          depth,
          expanded,
          empty: children.length === 0 && contents.length === 0,
        })
        if (expanded) {
          walk(folder.id, depth + 1)
        }
      }
      for (const item of itemsInFolder.get(parent) ?? []) {
        flat.push({ kind: 'item', id: item.key, depth, item })
      }
    }
    walk(null, 0)
    return flat
  }, [childFolders, itemsInFolder, collapsed])

  const toggle = useCallback((id: string, expanded: boolean) => {
    setCollapsed((state) => {
      const next = { ...state }
      if (expanded) {
        next[id] = true
      } else {
        delete next[id]
      }
      return next
    })
  }, [])

  /** Up and down walk the rows; left and right close and open, as they do in a file tree. */
  const onKeyDown = (event: React.KeyboardEvent) => {
    const row = rows[focused]
    const move = (to: number) => {
      const next = Math.max(0, Math.min(rows.length - 1, to))
      setFocused(next)
      const element = list.current?.querySelectorAll('.ag-tree-row')[next]
      ;(element as HTMLElement | undefined)?.focus()
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      move(focused + 1)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      move(focused - 1)
    } else if (event.key === 'ArrowRight' && row?.kind === 'folder' && !row.expanded) {
      event.preventDefault()
      toggle(row.id, false)
    } else if (event.key === 'ArrowLeft' && row?.kind === 'folder' && row.expanded) {
      event.preventDefault()
      toggle(row.id, true)
    } else if (event.key === 'Enter' && row?.kind === 'item') {
      event.preventDefault()
      onSelect(row.id)
    }
  }

  return (
    <div className="ag-tree">
      <div className="ag-tree-toolbar">
        <button type="button" className="ag-link" onClick={() => onCreateFolder(null)}>
          + folder
        </button>
      </div>

      <ul className="ag-tree-list" role="tree" ref={list} onKeyDown={onKeyDown}>
        {rows.map((row, index) => (
          <li
            key={`${row.kind}:${row.id}`}
            role="treeitem"
            aria-selected={row.kind === 'item' && selected.includes(row.id)}
            aria-expanded={row.kind === 'folder' ? row.expanded : undefined}
            tabIndex={index === focused ? 0 : -1}
            className={[
              'ag-tree-row',
              row.kind === 'folder' ? 'ag-tree-folder' : 'ag-tree-item',
              row.kind === 'item' && selected.includes(row.id) ? 'ag-tree-selected' : '',
            ]
              .filter(Boolean)
              .join(' ')}
            title={row.kind === 'item' ? (row.item.meta ?? row.item.label) : row.label}
            onFocus={() => setFocused(index)}
            onClick={() => (row.kind === 'item' ? onSelect(row.id) : toggle(row.id, row.expanded))}
          >
            {/* One guide per level, so the eye can follow a child back to what holds it. */}
            {Array.from({ length: row.depth }, (_, level) => (
              <span key={level} className="ag-tree-guide" />
            ))}

            <span className="ag-tree-twisty" aria-hidden="true">
              {row.kind === 'folder' && !row.empty ? (row.expanded ? '▾' : '▸') : ''}
            </span>

            {row.kind === 'folder' ? FOLDER_ICON : LEAF_ICON}

            <span className="ag-tree-label">{row.kind === 'folder' ? row.label : row.item.label}</span>
            {row.kind === 'item' && row.item.meta && <span className="ag-tree-meta">{row.item.meta}</span>}

            {/* Kept out of the way until the row is under the cursor: a tree is for reading. */}
            <span className="ag-tree-actions" onClick={(event) => event.stopPropagation()}>
              {row.kind === 'folder' ? (
                <>
                  <button
                    type="button"
                    className="ag-tree-tiny"
                    onClick={() => onCreateFolder(row.id)}
                    title="New folder inside this one"
                    aria-label={`New folder inside ${row.label}`}
                  >
                    +
                  </button>
                  <button
                    type="button"
                    className="ag-tree-tiny"
                    onClick={() => onDeleteFolder(row.id)}
                    title="Delete the folder; its contents move up"
                    aria-label={`Delete folder ${row.label}`}
                  >
                    ×
                  </button>
                </>
              ) : (
                <>
                  <select
                    className="ag-tree-move"
                    value={row.item.folder ?? ''}
                    aria-label={`Folder for ${row.item.label}`}
                    onChange={(event) => onMoveItem(row.id, event.target.value || null)}
                  >
                    <option value="">/</option>
                    {folders.map((folder) => (
                      <option key={folder.id} value={folder.id}>
                        {folder.name}
                      </option>
                    ))}
                  </select>
                  {renderActions?.(row.item)}
                </>
              )}
            </span>
          </li>
        ))}
      </ul>

      {items.length === 0 && <p className="muted">{emptyLabel}</p>}
    </div>
  )
}
