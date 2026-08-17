import { useMemo, useState } from 'react'

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

/**
 * Nested folders with their contents.
 *
 * Folders organise and nothing more — they mean nothing to the metamodel, do not affect
 * validation or C4 depth, and are not versioned (ADR-0009). That is why moving something
 * between them is a plain select, not a command, and why deleting one keeps its contents.
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
    for (const list of byParent.values()) {
      list.sort((a, b) => a.name.localeCompare(b.name))
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
    for (const list of byFolder.values()) {
      list.sort((a, b) => a.label.localeCompare(b.label))
    }
    return byFolder
  }, [items, folders])

  function renderItem(item: TreeItem) {
    return (
      <li key={item.key} className={selected.includes(item.key) ? 'ag-tree-selected' : ''}>
        <button type="button" className="ag-tree-item" onClick={() => onSelect(item.key)} title={item.meta}>
          <span className="ag-tree-label">{item.label}</span>
          {item.meta && <span className="ag-tree-meta">{item.meta}</span>}
        </button>
        <select
          className="ag-tree-move"
          value={item.folder ?? ''}
          aria-label={`Folder for ${item.label}`}
          onChange={(event) => onMoveItem(item.key, event.target.value || null)}
        >
          <option value="">/</option>
          {folders.map((folder) => (
            <option key={folder.id} value={folder.id}>
              {folder.name}
            </option>
          ))}
        </select>
        {renderActions?.(item)}
      </li>
    )
  }

  function renderFolder(folder: Folder, depth: number) {
    const isCollapsed = Boolean(collapsed[folder.id])
    const children = childFolders.get(folder.id) ?? []
    const contents = itemsInFolder.get(folder.id) ?? []
    return (
      <li key={folder.id} className="ag-tree-folder" style={{ marginLeft: depth === 0 ? 0 : '0.6rem' }}>
        <div className="ag-tree-folder-row">
          <button
            type="button"
            className="ag-tree-toggle"
            onClick={() => setCollapsed((state) => ({ ...state, [folder.id]: isCollapsed ? undefined! : true }))}
            aria-label={isCollapsed ? `Expand ${folder.name}` : `Collapse ${folder.name}`}
          >
            {isCollapsed ? '▸' : '▾'}
          </button>
          <span className="ag-tree-folder-name">{folder.name}</span>
          <button
            type="button"
            className="ag-link ag-tree-tiny"
            onClick={() => onCreateFolder(folder.id)}
            title="New folder inside this one"
          >
            +
          </button>
          <button
            type="button"
            className="ag-link ag-tree-tiny"
            onClick={() => onDeleteFolder(folder.id)}
            title="Delete the folder; its contents move up"
          >
            ×
          </button>
        </div>
        {!isCollapsed && (
          <ul className="ag-tree-list">
            {children.map((child) => renderFolder(child, depth + 1))}
            {contents.map(renderItem)}
          </ul>
        )}
      </li>
    )
  }

  const rootFolders = childFolders.get(null) ?? []
  const rootItems = itemsInFolder.get(null) ?? []

  return (
    <div className="ag-tree">
      <div className="ag-tree-toolbar">
        <button type="button" className="ag-link" onClick={() => onCreateFolder(null)}>
          + folder
        </button>
      </div>
      <ul className="ag-tree-list">
        {rootFolders.map((folder) => renderFolder(folder, 0))}
        {rootItems.map(renderItem)}
      </ul>
      {items.length === 0 && <p className="muted">{emptyLabel}</p>}
    </div>
  )
}
