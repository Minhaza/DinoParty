import { describe, expect, it } from 'vitest'

import { sanitizeChatText, validateChatText, validateGuessText } from './textRules'

describe('impostor text rules', () => {
  it('DI8_chat is 1–120 code points after stripping control characters and trimming', () => {
    expect(validateChatText('   ')).toBe('EMPTY')
    expect(validateChatText('​\n')).toBe('EMPTY')
    expect(validateChatText('a'.repeat(120))).toBeNull()
    expect(validateChatText('a'.repeat(121))).toBe('TOO_LONG')
    // An emoji is one code point but two UTF-16 units.
    expect(validateChatText('🦖'.repeat(120))).toBeNull()
    expect(sanitizeChatText('  hi\u0007 there ')).toBe('hi there')
  })

  it('DI11_guess is 1–80 code points after trim', () => {
    expect(validateGuessText(' ')).toBe('EMPTY')
    expect(validateGuessText('b'.repeat(80))).toBeNull()
    expect(validateGuessText('b'.repeat(81))).toBe('TOO_LONG')
  })
})
