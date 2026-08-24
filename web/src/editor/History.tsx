import { useMemo, useState } from 'react'

import type { Commit, CommitDiff, DiffChange, DiffField, Snapshot } from '../api/types.ts'

/**
 * The change log.
 *
 * <p>What it shows and what it cannot: every commit here is a change to the *model* — an element
 * created, a relationship drawn, a view renamed. Moving a box is not in it and never will be,
 * because layout is not versioned (ADR-0003). That is the decision that keeps this list readable
 * instead of drowning in geometry, and it is the reason the state of an old commit comes back as
 * a repository to read rather than a diagram to reopen.
 */

/** Compact enough for a narrow column, exact enough to be worth reading. */
function ago(iso: string): string {
  const then = new Date(iso).getTime()
  const seconds = Math.round((Date.now() - then) / 1000)
  if (!Number.isFinite(seconds)) return ''
  if (seconds < 60) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.round(hours / 24)
  if (days < 30) return `${days}d ago`
  return new Date(iso).toLocaleDateString()
}

function when(iso: string): string {
  return new Date(iso).toLocaleString()
}

interface HistoryListProps {
  commits: Commit[]
  loading: boolean
  /** The commit whose changes the panel is showing. */
  selected: string | null
  /** The commit it is being compared against, when the reader picked one. */
  base: string | null
  headCommitId: string | null
  onSelect: (commit: string) => void
  onCompare: (commit: string | null) => void
}

/**
 * The log itself: newest first, the way it is read.
 *
 * Every entry is attributed and every entry has a parent, so "what did this one do" is always
 * answerable without the reader choosing anything. Choosing a base is for the other question —
 * what changed between two points that are not neighbours.
 */
export function HistoryList({
  commits,
  loading,
  selected,
  base,
  headCommitId,
  onSelect,
  onCompare,
}: HistoryListProps) {
  return (
    <div className="ag-sidebar-body">
      <p className="ag-muted ag-history-note">
        Model changes only. Moving a box is layout and makes no commit.
      </p>
      {base && (
        <button type="button" className="ag-block-button" onClick={() => onCompare(null)}>
          Comparing from seq {commits.find((commit) => commit.id === base)?.seq ?? '?'} — clear
        </button>
      )}
      {loading && <p className="ag-muted">Reading the log…</p>}
      {!loading && commits.length === 0 && (
        <p className="ag-muted">Nothing has been committed yet.</p>
      )}
      <ul className="ag-history-list">
        {commits.map((commit) => (
          <li
            key={commit.id}
            className={
              'ag-history-entry' +
              (commit.id === selected ? ' ag-history-entry-selected' : '') +
              (commit.id === base ? ' ag-history-entry-base' : '')
            }
          >
            <button
              type="button"
              className="ag-history-open"
              onClick={() => onSelect(commit.id)}
              // The column is narrow and the message is what identifies the commit; the ellipsis
              // has to be recoverable without opening the entry.
              title={`${commit.message?.trim() || 'no message'}\n${commit.author} · ${when(commit.createdAt)}`}
            >
              <span className="ag-history-message">
                {commit.message?.trim() || <em>no message</em>}
              </span>
              <span className="ag-muted ag-history-meta">
                {commit.author} · {ago(commit.createdAt)} · #{commit.seq}
                {commit.id === headCommitId ? ' · head' : ''}
              </span>
            </button>
            <button
              type="button"
              className="ag-link ag-tree-tiny"
              title={commit.id === base ? 'Stop comparing from here' : 'Compare from this commit'}
              onClick={() => onCompare(commit.id === base ? null : commit.id)}
            >
              {commit.id === base ? '×' : '⇄'}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** `property:owner` reads as "owner"; everything else is already the word for it. */
function fieldLabel(field: string): string {
  return field.startsWith('property:') ? field.slice('property:'.length) : field
}

function FieldRow({ field }: { field: DiffField }) {
  const label = <span className="ag-diff-field">{fieldLabel(field.field)}</span>
  // Set-valued attributes arrive one entry per element gained or lost, with the other side null.
  // Rendering those as "null → Kafka" would be worse than useless.
  if (field.before == null && field.after != null) {
    return (
      <>
        {label}
        <span className="ag-diff-added">+ {field.after}</span>
      </>
    )
  }
  if (field.after == null && field.before != null) {
    return (
      <>
        {label}
        <span className="ag-diff-removed">− {field.before}</span>
      </>
    )
  }
  return (
    <>
      {label}
      <span className="ag-diff-removed">{field.before ?? '—'}</span>
      <span className="ag-muted"> → </span>
      <span className="ag-diff-added">{field.after ?? '—'}</span>
    </>
  )
}

const MARK: Record<DiffChange['verb'], string> = { added: '+', removed: '−', changed: '~' }

function ChangeRow({ change }: { change: DiffChange }) {
  return (
    <li className={`ag-diff-change ag-diff-verb-${change.verb}`}>
      <div className="ag-diff-head">
        <span className="ag-diff-mark">{MARK[change.verb]}</span>
        <strong>{change.label || <em>unnamed</em>}</strong>
        <span className="ag-muted"> {change.type}</span>
        {change.source && (
          <span className="ag-muted">
            {' '}
            · {change.source} → {change.target}
          </span>
        )}
      </div>
      {change.fields.length > 0 && (
        <ul className="ag-diff-fields">
          {change.fields.map((field, index) => (
            <li key={`${field.field}-${index}`}>
              <FieldRow field={field} />
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

const SUBJECTS: { key: DiffChange['subject']; heading: string }[] = [
  { key: 'element', heading: 'Elements' },
  { key: 'relationship', heading: 'Relationships' },
  { key: 'view', heading: 'Views' },
]

interface HistoryPanelProps {
  diff: CommitDiff | undefined
  snapshot: Snapshot | undefined
  mode: 'changes' | 'state'
  onMode: (mode: 'changes' | 'state') => void
  loading: boolean
  error: string | null
  onClose: () => void
}

/**
 * What one commit changed, or what separates two, with the state of the day behind a tab.
 *
 * Over the canvas rather than in the sidebar, because a before-and-after needs the width — and
 * because reading history is a mode the editor is in, not a corner of it.
 */
export function HistoryPanel({
  diff,
  snapshot,
  mode,
  onMode,
  loading,
  error,
  onClose,
}: HistoryPanelProps) {
  const [query, setQuery] = useState('')

  const grouped = useMemo(() => {
    const changes = diff?.changes ?? []
    return SUBJECTS.map((subject) => ({
      ...subject,
      changes: changes.filter((change) => change.subject === subject.key),
    })).filter((group) => group.changes.length > 0)
  }, [diff])

  /**
   * A repository of any size has hundreds of elements, and a state view that lists them all is a
   * wall rather than an answer. Narrowed here rather than on the server: the state is one request
   * either way, and the question — "was this thing here yet" — is asked of a list already in hand.
   */
  const narrowed = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const elements = (snapshot?.elements ?? []).filter(
      (element) =>
        !needle ||
        element.name.toLowerCase().includes(needle) ||
        element.type.toLowerCase().includes(needle),
    )
    const views = (snapshot?.views ?? []).filter(
      (view) => !needle || view.name.toLowerCase().includes(needle),
    )
    return { elements, views }
  }, [snapshot, query])

  const to = diff?.to ?? snapshot?.commit ?? null

  return (
    <div className="ag-history-panel" role="dialog" aria-label="History">
      <header>
        <strong>{to ? to.message?.trim() || 'no message' : 'History'}</strong>
        {to && (
          <span className="ag-muted">
            {' '}
            {to.author} · {when(to.createdAt)} · #{to.seq}
          </span>
        )}
        <button type="button" className="ag-banner-close" onClick={onClose} aria-label="Dismiss">
          ×
        </button>
      </header>

      <div className="ag-tabs" role="tablist">
        {(['changes', 'state'] as const).map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={mode === tab}
            className={mode === tab ? 'ag-tab ag-tab-active' : 'ag-tab'}
            onClick={() => onMode(tab)}
          >
            {tab === 'changes' ? 'Changes' : 'State at this commit'}
          </button>
        ))}
      </div>

      {error && <p className="ag-diff-error">{error}</p>}
      {loading && <p className="ag-muted">Reconstructing…</p>}

      {!loading && mode === 'changes' && diff && (
        <div className="ag-history-body">
          <p className="ag-muted">
            {diff.from ? (
              <>
                Since #{diff.from.seq} — {diff.from.message?.trim() || 'no message'}
              </>
            ) : (
              'The first commit: everything in it appeared here.'
            )}
          </p>
          <p className="ag-diff-summary">
            <span className="ag-diff-added">+{diff.summary.added}</span>{' '}
            <span className="ag-diff-removed">−{diff.summary.removed}</span>{' '}
            <span className="ag-diff-changed">~{diff.summary.changed}</span>
          </p>
          {grouped.length === 0 && (
            // Two commands that cancel out leave no trace, which is the point of writing the
            // history by diffing rather than by recording what was attempted.
            <p className="ag-muted">These two states are identical.</p>
          )}
          {grouped.map((group) => (
            <section key={group.key}>
              <h4>
                {group.heading} <span className="ag-muted">{group.changes.length}</span>
              </h4>
              <ul className="ag-diff-changes">
                {group.changes.map((change) => (
                  <ChangeRow key={`${change.subject}-${change.key}`} change={change} />
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}

      {!loading && mode === 'state' && snapshot && (
        <div className="ag-history-body">
          <p className="ag-muted">
            The model as it stood. Not the diagram: coordinates are not versioned, so the views
            below are the ones you have now, however they were arranged then.
          </p>
          <p className="ag-diff-summary">
            {snapshot.elements.length} elements · {snapshot.relationships.length} relationships ·{' '}
            {snapshot.views.length} views
          </p>
          <label className="ag-field">
            <span>Search</span>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="name or type"
            />
          </label>
          <section>
            <h4>
              Elements{' '}
              <span className="ag-muted">
                {query
                  ? `${narrowed.elements.length} of ${snapshot.elements.length}`
                  : snapshot.elements.length}
              </span>
            </h4>
            <ul className="ag-diff-changes">
              {narrowed.elements.map((element) => (
                <li key={element.key} className="ag-diff-change">
                  <strong>{element.name}</strong> <span className="ag-muted">{element.type}</span>
                </li>
              ))}
            </ul>
          </section>
          <section>
            <h4>
              Views{' '}
              <span className="ag-muted">
                {query ? `${narrowed.views.length} of ${snapshot.views.length}` : snapshot.views.length}
              </span>
            </h4>
            <ul className="ag-diff-changes">
              {narrowed.views.map((view) => (
                <li key={view.key} className="ag-diff-change">
                  <strong>{view.name}</strong>{' '}
                  <span className="ag-muted">
                    {view.viewpoint} · {view.elements} elements
                  </span>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}
    </div>
  )
}
