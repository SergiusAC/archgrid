/**
 * The diagram as a file.
 *
 * A view is already SVG on screen, so exporting is mostly a matter of taking a copy and undoing
 * the things that belong to the editor rather than to the picture: the pan and zoom transform,
 * the resize grips, the alignment guides. Nothing here knows about the diagramming library — it
 * is handed an `<svg>` and the box worth keeping (ADR-0010).
 *
 * Needed before phase 5 in any case: arc42 and Structurizr-style documentation embed diagrams,
 * and a knowledge platform that cannot hand its diagrams to a document is a dead end.
 */

/** Room around the drawing, so the outermost box does not touch the edge of the image. */
const PADDING = 24

/**
 * Every stylesheet rule the app has, embedded in the file.
 *
 * All of it rather than the rules that match: an exported diagram is read by whatever opens it —
 * a browser, Word, Confluence — and none of them will consult our stylesheet. Filtering by
 * selector is where an export loses the one rule that made the layer colours work.
 */
function styleSheetText(): string {
  const parts: string[] = []
  for (const sheet of Array.from(document.styleSheets)) {
    try {
      for (const rule of Array.from(sheet.cssRules)) {
        parts.push(rule.cssText)
      }
    } catch {
      // A cross-origin sheet cannot be read. None of ours are, and a font sheet we cannot read
      // is not worth failing an export over.
    }
  }
  return parts.join('\n')
}

function fileName(name: string, extension: string): string {
  const slug = name.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
  return `${slug || 'diagram'}.${extension}`
}

function save(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  // Late enough for the click to have been handled, early enough not to leak the buffer.
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

interface Box {
  x: number
  y: number
  width: number
  height: number
}

/**
 * A copy of the diagram, cropped to its contents and carrying its own styles.
 *
 * The clone matters: stripping the transform off the live SVG would move the diagram out from
 * under the user while they watched.
 */
function drawing(root: SVGSVGElement, box: Box): SVGSVGElement {
  const copy = root.cloneNode(true) as SVGSVGElement

  // The editor's furniture, which is not part of the picture. The grid included: dots are there
  // to help someone place a box, and an exported diagram is past that.
  for (const node of Array.from(
    copy.querySelectorAll('.joint-tools-layer, .joint-front-layer, .joint-grid-layer, .ag-guides, .ag-marquee'),
  )) {
    node.remove()
  }
  // Selection is a state of the editor, not a property of the diagram.
  for (const node of Array.from(copy.querySelectorAll('.ag-node-selected, .ag-link-selected'))) {
    node.classList.remove('ag-node-selected', 'ag-link-selected')
  }
  // The pan and zoom the user happens to be at; the viewBox below replaces it with the contents.
  copy.querySelector('.joint-layers')?.removeAttribute('transform')

  const width = box.width + PADDING * 2
  const height = box.height + PADDING * 2
  copy.setAttribute('viewBox', `${box.x - PADDING} ${box.y - PADDING} ${width} ${height}`)
  copy.setAttribute('width', String(width))
  copy.setAttribute('height', String(height))
  copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg')

  // Opaque, or every viewer that assumes a dark background renders black text on black.
  const background = document.createElementNS('http://www.w3.org/2000/svg', 'rect')
  background.setAttribute('x', String(box.x - PADDING))
  background.setAttribute('y', String(box.y - PADDING))
  background.setAttribute('width', String(width))
  background.setAttribute('height', String(height))
  background.setAttribute('fill', '#ffffff')
  copy.insertBefore(background, copy.firstChild)

  const style = document.createElementNS('http://www.w3.org/2000/svg', 'style')
  style.textContent = styleSheetText()
  copy.insertBefore(style, copy.firstChild)

  return copy
}

/** The pixel density of a PNG. Two, so the export survives being enlarged in a document. */
const PNG_SCALE = 2

export async function downloadDiagram(
  root: SVGSVGElement,
  box: Box,
  name: string,
  format: 'svg' | 'png',
): Promise<void> {
  const source = new XMLSerializer().serializeToString(drawing(root, box))

  if (format === 'svg') {
    save(new Blob([source], { type: 'image/svg+xml;charset=utf-8' }), fileName(name, 'svg'))
    return
  }

  const width = (box.width + PADDING * 2) * PNG_SCALE
  const height = (box.height + PADDING * 2) * PNG_SCALE
  // A data URL rather than a blob URL: an SVG drawn from a blob taints the canvas in some
  // browsers, and a tainted canvas cannot be read back — the export would fail at the last step.
  const encoded = `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(source)))}`

  await new Promise<void>((resolve, reject) => {
    const image = new Image()
    image.onload = () => {
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d')
      if (!context) {
        reject(new Error('This browser cannot draw the diagram to an image.'))
        return
      }
      context.drawImage(image, 0, 0, width, height)
      canvas.toBlob((blob) => {
        if (blob) {
          save(blob, fileName(name, 'png'))
          resolve()
        } else {
          reject(new Error('The diagram could not be turned into a PNG.'))
        }
      }, 'image/png')
    }
    image.onerror = () => reject(new Error('The diagram could not be rendered for export.'))
    image.src = encoded
  })
}
