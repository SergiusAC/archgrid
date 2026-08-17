import { useEffect, useState } from 'react'

import type { ModelElement, ModelRelationship, Profile } from '../api/types.ts'
import type { Metamodel } from '../metamodel/Metamodel.ts'

interface PropertiesProps {
  element: ModelElement | null
  selectedCount: number
  metamodel: Metamodel
  profile: Profile | undefined
  relationships: ModelRelationship[]
  elementsByKey: Record<string, ModelElement>
  busy: boolean
  onRename: (key: string, name: string) => void
  onDocumentation: (key: string, documentation: string) => void
  onProperty: (key: string, property: string, value: string | null) => void
  onSelect: (key: string) => void
}

/**
 * Everything about the selected element that is not its position.
 *
 * Edits commit on blur rather than on keystroke: every change here is a command and therefore
 * a commit, and a history with one entry per character typed is a history nobody reads.
 */
export default function Properties({
  element,
  selectedCount,
  metamodel,
  profile,
  relationships,
  elementsByKey,
  busy,
  onRename,
  onDocumentation,
  onProperty,
  onSelect,
}: PropertiesProps) {
  const [name, setName] = useState('')
  const [documentation, setDocumentation] = useState('')
  const [newKey, setNewKey] = useState('')
  const [newValue, setNewValue] = useState('')

  useEffect(() => {
    setName(element?.name ?? '')
    setDocumentation(element?.documentation ?? '')
    setNewKey('')
    setNewValue('')
  }, [element?.key, element?.name, element?.documentation])

  if (selectedCount > 1) {
    return (
      <aside className="ag-panel ag-properties">
        <p className="muted">{selectedCount} elements selected.</p>
      </aside>
    )
  }

  if (!element) {
    return (
      <aside className="ag-panel ag-properties">
        <p className="muted">Select an element to see and edit its properties.</p>
      </aside>
    )
  }

  const type = metamodel.elementType(element.conceptType)
  const used = relationships.filter(
    (relationship) => relationship.source === element.key || relationship.target === element.key,
  )
  const attributeKeys = new Set((profile?.attributes ?? []).map((attribute) => attribute.key))
  const extraProperties = Object.entries(element.properties).filter(([key]) => !attributeKeys.has(key))

  return (
    <aside className="ag-panel ag-properties">
      <div className="ag-props-type">
        {element.profile && <span className="ag-stereotype">«{element.profile}»</span>}{' '}
        {type?.name ?? element.conceptType}
      </div>
      <code className="ag-props-key">{element.key}</code>

      <label className="ag-field">
        <span>Name</span>
        <input
          value={name}
          disabled={busy}
          onChange={(event) => setName(event.target.value)}
          onBlur={() => {
            const trimmed = name.trim()
            if (trimmed && trimmed !== element.name) {
              onRename(element.key, trimmed)
            } else if (!trimmed) {
              setName(element.name)
            }
          }}
        />
      </label>

      <label className="ag-field">
        <span>Documentation</span>
        <textarea
          rows={4}
          value={documentation}
          disabled={busy}
          onChange={(event) => setDocumentation(event.target.value)}
          onBlur={() => {
            if (documentation !== (element.documentation ?? '')) {
              onDocumentation(element.key, documentation)
            }
          }}
        />
      </label>

      {profile && profile.attributes.length > 0 && (
        <section className="ag-props-group">
          {/* Declared by the profile, so they get real fields rather than being buried in the
              generic property list — that is most of what a specialisation is worth. */}
          <h3>«{profile.name}» attributes</h3>
          {profile.attributes.map((attribute) => (
            <AttributeField
              key={attribute.key}
              attribute={attribute}
              value={element.properties[attribute.key] ?? ''}
              disabled={busy}
              onCommit={(value) => onProperty(element.key, attribute.key, value === '' ? null : value)}
            />
          ))}
        </section>
      )}

      <section className="ag-props-group">
        <h3>Properties</h3>
        {extraProperties.length === 0 && <p className="muted">None.</p>}
        {extraProperties.map(([key, value]) => (
          <div className="ag-props-row" key={key}>
            <code>{key}</code>
            <span>{value}</span>
            <button
              type="button"
              className="ag-link"
              disabled={busy}
              onClick={() => onProperty(element.key, key, null)}
              title="Remove this property"
            >
              remove
            </button>
          </div>
        ))}
        <form
          className="ag-props-add"
          onSubmit={(event) => {
            event.preventDefault()
            if (newKey.trim()) {
              onProperty(element.key, newKey.trim(), newValue)
              setNewKey('')
              setNewValue('')
            }
          }}
        >
          <input value={newKey} onChange={(event) => setNewKey(event.target.value)} placeholder="key" />
          <input value={newValue} onChange={(event) => setNewValue(event.target.value)} placeholder="value" />
          <button type="submit" disabled={busy || !newKey.trim()}>
            Add
          </button>
        </form>
      </section>

      <section className="ag-props-group">
        <h3>Used in {used.length} relationship{used.length === 1 ? '' : 's'}</h3>
        {used.length === 0 && <p className="muted">Nothing references it.</p>}
        <ul className="ag-props-usage">
          {used.map((relationship) => {
            const outgoing = relationship.source === element.key
            const other = elementsByKey[outgoing ? relationship.target : relationship.source]
            return (
              <li key={relationship.key}>
                <span className="ag-muted">{relationship.type}</span> {outgoing ? '→' : '←'}{' '}
                <button type="button" className="ag-link" onClick={() => other && onSelect(other.key)}>
                  {other?.name ?? '?'}
                </button>
              </li>
            )
          })}
        </ul>
      </section>
    </aside>
  )
}

interface AttributeFieldProps {
  attribute: { key: string; name: string; type: string }
  value: string
  disabled: boolean
  onCommit: (value: string) => void
}

/**
 * One profile attribute, rendered per its declared type.
 *
 * The six types are the exchange format's, so whatever is entered here survives an export.
 */
function AttributeField({ attribute, value, disabled, onCommit }: AttributeFieldProps) {
  const [draft, setDraft] = useState(value)

  useEffect(() => setDraft(value), [value])

  if (attribute.type === 'boolean') {
    return (
      <label className="ag-field ag-inline">
        <input
          type="checkbox"
          checked={draft === 'true'}
          disabled={disabled}
          onChange={(event) => {
            const next = String(event.target.checked)
            setDraft(next)
            onCommit(next)
          }}
        />
        <span>{attribute.name}</span>
      </label>
    )
  }

  const inputType =
    attribute.type === 'number' ? 'number' : attribute.type === 'date' ? 'date' : attribute.type === 'time' ? 'time' : 'text'

  return (
    <label className="ag-field">
      <span>
        {attribute.name}
        {attribute.type === 'currency' && <em className="ag-muted"> (currency)</em>}
      </span>
      <input
        type={inputType}
        value={draft}
        disabled={disabled}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => {
          if (draft !== value) {
            onCommit(draft)
          }
        }}
      />
    </label>
  )
}
