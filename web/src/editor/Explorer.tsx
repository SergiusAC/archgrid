import { useMemo, useState } from 'react'

import type { Folder, ModelElement, ModelRelationship } from '../api/types.ts'
import type { Metamodel } from '../metamodel/Metamodel.ts'
import FolderTree, { type TreeItem } from './FolderTree.tsx'

interface ExplorerProps {
  elements: ModelElement[]
  relationships: ModelRelationship[]
  folders: Folder[]
  /** Which elements the open view already shows. */
  onView: Set<string>
  metamodel: Metamodel
  selected: string[]
  onSelect: (key: string) => void
  onAddToView: (key: string) => void
  onCreateFolder: (parent: string | null) => void
  onDeleteFolder: (id: string) => void
  onMoveItem: (key: string, folder: string | null) => void
}

/**
 * The repository: every element, whether or not the open view shows it.
 *
 * This is where the product's central claim is visible. The model is the thing; a view is one
 * diagram over part of it. A browser that listed only what is currently drawn would quietly
 * teach the opposite (ADR-0008, ADR-0009).
 */
export default function Explorer({
  elements,
  relationships,
  folders,
  onView,
  metamodel,
  selected,
  onSelect,
  onAddToView,
  onCreateFolder,
  onDeleteFolder,
  onMoveItem,
}: ExplorerProps) {
  const [query, setQuery] = useState('')

  const degree = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const relationship of relationships) {
      counts[relationship.source] = (counts[relationship.source] ?? 0) + 1
      counts[relationship.target] = (counts[relationship.target] ?? 0) + 1
    }
    return counts
  }, [relationships])

  const items: TreeItem[] = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return elements
      .filter(
        (element) =>
          !needle ||
          // Names, keys and type: an architect looking for "kafka" may remember any of them.
          element.name.toLowerCase().includes(needle) ||
          element.key.includes(needle) ||
          element.conceptType.toLowerCase().includes(needle) ||
          (element.profile ?? '').toLowerCase().includes(needle),
      )
      .map((element) => ({
        key: element.key,
        label: element.name,
        meta:
          (element.profile ?? metamodel.elementType(element.conceptType)?.name ?? element.conceptType) +
          (degree[element.key] ? ` · ${degree[element.key]}` : ''),
        folder: element.folder ?? null,
      }))
  }, [elements, query, metamodel, degree])

  return (
    <div className="ag-sidebar-body">
      <label className="ag-field">
        <span>Search</span>
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="name, key or type"
        />
      </label>
      <p className="ag-muted ag-explorer-count">
        {query ? `${items.length} of ${elements.length}` : `${elements.length}`} in the repository
      </p>

      <FolderTree
        folders={folders}
        items={items}
        selected={selected}
        emptyLabel="The repository is empty. Add an element from the palette."
        onSelect={onSelect}
        onCreateFolder={onCreateFolder}
        onDeleteFolder={onDeleteFolder}
        onMoveItem={onMoveItem}
        renderActions={(item) =>
          onView.has(item.key) ? (
            <span className="ag-tree-onview" title="Already on the open view">
              ●
            </span>
          ) : (
            <button
              type="button"
              className="ag-link ag-tree-tiny"
              onClick={() => onAddToView(item.key)}
              title="Show this element on the open view"
            >
              add
            </button>
          )
        }
      />
    </div>
  )
}
