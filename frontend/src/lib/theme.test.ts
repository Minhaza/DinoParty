import { resolveDark } from './theme'

describe('resolveDark', () => {
  it('follows the system when preference is system', () => {
    expect(resolveDark('system', true)).toBe(true)
    expect(resolveDark('system', false)).toBe(false)
  })

  it('ignores the system for explicit preferences', () => {
    expect(resolveDark('dark', false)).toBe(true)
    expect(resolveDark('light', true)).toBe(false)
  })
})
