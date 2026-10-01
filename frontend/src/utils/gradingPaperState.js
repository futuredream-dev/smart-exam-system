/** Keep an unsaved draft only when the same paper is refreshed after review or AI grading. */
export function resolveFinalComment(current, server, preserveDraft = false) {
  const saved = server ?? ''
  return { text: preserveDraft ? current : saved, saved }
}
