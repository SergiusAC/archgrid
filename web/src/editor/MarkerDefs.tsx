/**
 * SVG marker definitions for the ArchiMate arrowheads.
 *
 * Rendered once into a zero-size SVG at the document level. Markers referenced by `url(#id)`
 * resolve across SVG elements in the same document, so React Flow's edges can use them without
 * a custom edge component.
 *
 * `context-stroke` makes each marker take the colour of the edge it is attached to, so
 * selection and hover states carry through to the arrowhead instead of leaving a stranded
 * black triangle behind.
 */
export default function MarkerDefs() {
  return (
    <svg className="ag-marker-defs" aria-hidden="true" focusable="false">
      <defs>
        {/* Triggering, Flow, Assignment: solid filled head. */}
        <marker
          id="ag-arrow-filled"
          viewBox="0 0 12 12"
          refX="11"
          refY="6"
          markerWidth="8"
          markerHeight="8"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <path d="M 0 1 L 12 6 L 0 11 z" fill="context-stroke" />
        </marker>

        {/* Serving, Influence: open head, two strokes rather than a filled shape. */}
        <marker
          id="ag-arrow-open"
          viewBox="0 0 12 12"
          refX="11"
          refY="6"
          markerWidth="10"
          markerHeight="10"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <path d="M 1 1 L 11 6 L 1 11" fill="none" stroke="context-stroke" strokeWidth="1.4" />
        </marker>

        {/* Access: the same open head, smaller, because access is the lightest dependency. */}
        <marker
          id="ag-arrow-open-small"
          viewBox="0 0 10 10"
          refX="9"
          refY="5"
          markerWidth="7"
          markerHeight="7"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <path d="M 1 1 L 9 5 L 1 9" fill="none" stroke="context-stroke" strokeWidth="1.3" />
        </marker>

        {/* Realization and Specialization: hollow triangle, filled with the canvas colour. */}
        <marker
          id="ag-triangle-hollow"
          viewBox="0 0 14 14"
          refX="13"
          refY="7"
          markerWidth="11"
          markerHeight="11"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <path
            d="M 1 1 L 13 7 L 1 13 z"
            fill="var(--bg)"
            stroke="context-stroke"
            strokeWidth="1.3"
          />
        </marker>

        {/* Composition: filled diamond at the whole end. */}
        <marker
          id="ag-diamond-filled"
          viewBox="0 0 16 12"
          refX="1"
          refY="6"
          markerWidth="12"
          markerHeight="9"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <path d="M 1 6 L 8 1 L 15 6 L 8 11 z" fill="context-stroke" />
        </marker>

        {/* Aggregation: the same diamond, hollow. */}
        <marker
          id="ag-diamond-hollow"
          viewBox="0 0 16 12"
          refX="1"
          refY="6"
          markerWidth="12"
          markerHeight="9"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <path
            d="M 1 6 L 8 1 L 15 6 L 8 11 z"
            fill="var(--bg)"
            stroke="context-stroke"
            strokeWidth="1.3"
          />
        </marker>

        {/* Assignment: filled ball at the assigned-from end. */}
        <marker
          id="ag-ball"
          viewBox="0 0 10 10"
          refX="5"
          refY="5"
          markerWidth="8"
          markerHeight="8"
          orient="auto-start-reverse"
          markerUnits="userSpaceOnUse"
        >
          <circle cx="5" cy="5" r="3.5" fill="context-stroke" />
        </marker>
      </defs>
    </svg>
  )
}
