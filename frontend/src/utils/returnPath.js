export function safeReturnPath(value) {
  if (typeof value !== 'string' || !value.startsWith('/')) return null
  try {
    let decoded = value
    for (let count = 0; count < 4; count++) { const next = decodeURIComponent(decoded); if (next === decoded) break; decoded = next }
    if (!decoded.startsWith('/') || decoded.startsWith('//') || /[\\\x00-\x1f\x7f]/.test(decoded) || /%[0-9a-f]{2}/i.test(decoded)) return null
    const target = new URL(decoded, 'https://internal.invalid')
    if (target.origin !== 'https://internal.invalid' || /^\/login\/?$/i.test(target.pathname)) return null
    return value
  } catch { return null }
}
