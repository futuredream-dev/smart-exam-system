/** Compatibility CLI for current student lifecycle regressions; logic/compiled handlers only, no browser E2E. */
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const files = ['studentEntryViews', 'studentBoundaryViews', 'studentPaperView', 'studentAnswerDraft', 'studentDraftAuth', 'studentHttpSession', 'examClock', 'loginReturn']
const result = spawnSync(process.execPath, ['--test', ...files.map(name => fileURLToPath(new URL(`../tests/${name}.test.mjs`, import.meta.url)))], { stdio: 'inherit' })
if (result.error) throw result.error
process.exitCode = result.status ?? 1