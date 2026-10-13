/**
 * Turns uncaught errors and unhandled promise rejections into small, scrubbed records. Error
 * messages can echo user input or URLs, so everything is length-limited and query strings and
 * fragments are stripped before it leaves the browser.
 */
export interface ErrorRecord {
  kind: 'uncaught-error' | 'unhandled-rejection'
  type: string
  message: string
  stack?: string
  source?: string
}

const MAX_MESSAGE = 300
const MAX_STACK = 2000

function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max)}…` : value
}

/** Drops `?query` and `#fragment` parts from any URL-like text. */
export function stripUrlDetails(text: string): string {
  return text.replace(/(https?:\/\/[^\s?#)'"]+)[?#][^\s)'"]*/g, '$1')
}

function describe(reason: unknown): { type: string; message: string; stack?: string } {
  if (reason instanceof Error) {
    return { type: reason.name, message: reason.message, stack: reason.stack }
  }
  // Non-Error rejections (strings, plain objects) are described by type only, never serialized.
  return { type: typeof reason, message: typeof reason === 'string' ? reason : 'non-error value' }
}

function toRecord(kind: ErrorRecord['kind'], reason: unknown, source?: string): ErrorRecord {
  const { type, message, stack } = describe(reason)
  return {
    kind,
    type,
    message: truncate(stripUrlDetails(message), MAX_MESSAGE),
    stack: stack ? truncate(stripUrlDetails(stack), MAX_STACK) : undefined,
    source: source ? stripUrlDetails(source) : undefined,
  }
}

/** Registers window listeners and returns a cleanup function. */
export function installErrorHandlers(
  target: Pick<Window, 'addEventListener' | 'removeEventListener'>,
  report: (record: ErrorRecord) => void,
): () => void {
  const onError = (event: Event) => {
    const e = event as ErrorEvent
    report(toRecord('uncaught-error', e.error ?? e.message, e.filename))
  }
  const onRejection = (event: Event) => {
    report(toRecord('unhandled-rejection', (event as PromiseRejectionEvent).reason))
  }
  target.addEventListener('error', onError)
  target.addEventListener('unhandledrejection', onRejection)
  return () => {
    target.removeEventListener('error', onError)
    target.removeEventListener('unhandledrejection', onRejection)
  }
}
