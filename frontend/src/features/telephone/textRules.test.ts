import { describe, expect, it } from 'vitest'

import {
  codePointLength,
  sanitizeTelephoneText,
  TELEPHONE_TEXT_MAX,
  validateTelephoneText,
} from './textRules'

describe('Telephone text rules [TP-14]', () => {
  it('TP14 strips control and format characters, then trims', () => {
    expect(sanitizeTelephoneText('  a\u0007b​c‮\n ')).toBe('abc')
    expect(sanitizeTelephoneText('\t\n')).toBe('')
  })

  it('TP14 counts Unicode code points, not UTF-16 units', () => {
    expect(codePointLength('🦖🦕')).toBe(2)
    expect('🦖🦕'.length).toBe(4)
    expect(codePointLength('恐竜のパーティー')).toBe(8)
    // A combining accent is its own code point.
    expect(codePointLength('é')).toBe(2)
  })

  it('TP14 accepts 1–140 code points and preserves non-English text', () => {
    expect(validateTelephoneText('')).toBe('EMPTY')
    expect(validateTelephoneText('   ​ ')).toBe('EMPTY')
    expect(validateTelephoneText('Динозавр печёт торт')).toBeNull()
    expect(validateTelephoneText('🦖'.repeat(TELEPHONE_TEXT_MAX))).toBeNull()
    expect(validateTelephoneText('🦖'.repeat(TELEPHONE_TEXT_MAX + 1))).toBe('TOO_LONG')
    expect(sanitizeTelephoneText(' Динозавр 🦖 ')).toBe('Динозавр 🦖')
  })
})
