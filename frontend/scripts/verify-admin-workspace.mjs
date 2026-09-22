import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'src')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const router = read('router/index.js')
const layout = read('layouts/AppLayout.vue')
const view = read('views/admin/AdminTeachersView.vue')
const api = read('api/admin.js')

assert.match(router, /path:\s*'\/admin'[\s\S]*component:\s*AdminTeachersView/)
assert.match(router, /path:\s*'change-password'[\s\S]*name:\s*'admin-change-password'/)
assert.doesNotMatch(router, /path:\s*'admin\/(?:exams|question-banks|grading|results)'/)

const adminNav = layout.match(/role === 'ADMIN'[\s\S]*?<\/nav>/)?.[0] || ''
assert.deepEqual([...adminNav.matchAll(/to="([^"]+)"/g)].map(match => match[1]), ['/admin'])
assert.match(layout, /accountPasswordPath/)

for (const state of ['loading', 'notice', 'rowBusyId']) assert.match(view, new RegExp(`\\b${state}\\b`))
assert.match(view, /TeacherModal/)
assert.match(view, /confirmation/)
assert.doesNotMatch(view, /\bconfirm\s*\(/)

for (const endpoint of ['/admin/teachers']) assert.match(api, new RegExp(endpoint.replaceAll('/', '\\/')))
assert.doesNotMatch(api, /\/admin\/(?:exams|question-banks|grading|results|students)/)

console.log('PASS admin workspace structural and permission constraints')
