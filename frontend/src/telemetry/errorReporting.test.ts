import { describe, expect, it, vi } from 'vitest'

import { installErrorHandlers, stripUrlDetails, type ErrorRecord } from './errorReporting'

function setup() {
  const target = new EventTarget()
  const report = vi.fn<(record: ErrorRecord) => void>()
  const cleanup = installErrorHandlers(target as unknown as Window, report)
  return { target, report, cleanup }
}

describe('installErrorHandlers', () => {
  it('reports uncaught errors without query strings', () => {
    const { target, report } = setup()
    const error = new TypeError('failed https://x.test/a?token=secret#frag')
    target.dispatchEvent(Object.assign(new Event('error'), { error, message: error.message }))
    const record = report.mock.calls[0][0]
    expect(record.kind).toBe('uncaught-error')
    expect(record.type).toBe('TypeError')
    expect(record.message).toBe('failed https://x.test/a')
    expect(JSON.stringify(record)).not.toContain('secret')
  })

  it('reports unhandled rejections and never serializes non-Error values', () => {
    const { target, report } = setup()
    target.dispatchEvent(
      Object.assign(new Event('unhandledrejection'), { reason: { token: 'abc' } }),
    )
    const record = report.mock.calls[0][0]
    expect(record.kind).toBe('unhandled-rejection')
    expect(record.message).toBe('non-error value')
    expect(JSON.stringify(record)).not.toContain('abc')
  })

  it('truncates long messages and stops reporting after cleanup', () => {
    const { target, report, cleanup } = setup()
    target.dispatchEvent(
      Object.assign(new Event('error'), { error: new Error('x'.repeat(1000)), message: '' }),
    )
    expect(report.mock.calls[0][0].message.length).toBeLessThanOrEqual(301)
    cleanup()
    target.dispatchEvent(Object.assign(new Event('error'), { error: new Error('y'), message: '' }))
    expect(report).toHaveBeenCalledTimes(1)
  })
})

describe('stripUrlDetails', () => {
  it('keeps path but drops query and fragment', () => {
    expect(stripUrlDetails('GET http://h/p?a=1&b=2 failed')).toBe('GET http://h/p failed')
  })
})
