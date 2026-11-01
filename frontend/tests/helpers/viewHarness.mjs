import { readFile } from 'node:fs/promises'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from 'vue/server-renderer'

export const modal = { props: ['title'], setup: (props, { slots }) => () => vue.h('section', [vue.h('h2', props.title), slots.default?.(), slots.footer?.()]) }
export const settle = async () => { for (let i = 0; i < 12; i++) await vue.nextTick() }
const source = file => readFile(new URL(`../../src/${file}`, import.meta.url), 'utf8')

export async function setupView(file, imports = {}, props = {}) {
  const mounted = [], unmount = []
  const bindings = { vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn), onUnmounted: fn => unmount.push(fn) }, ...imports }
  const code = compileScript(parse(await source(file)).descriptor, { id: 'closeout', genDefaultAs: 'component' }).content
    .replace(/import\s*{([^}]+)}\s*from\s*['"]([^'"]+)['"];?/g, (_, names, from) => `const {${names.replace(/\bas\b/g, ':')}} = imports[${JSON.stringify(from)}] || {};`)
    .replace(/import\s+(\w+)\s+from\s*['"]([^'"]+)['"];?/g, (_, name, from) => `const ${name} = imports[${JSON.stringify(from)}] || ${name === 'TeacherModal' ? 'modal' : '{ render: () => null }'};`)
  const component = new Function('imports', 'sessionStorage', 'modal', `${code};return component`)(bindings, { getItem: () => null, removeItem() {} }, modal)
  const state = component.setup(props, { expose() {}, emit() {} })
  return { state, mount: async () => { for (const fn of mounted) await fn(); await settle() }, dispose: () => unmount.forEach(fn => fn()) }
}

export async function renderView(file, state) {
  const descriptor = parse(await source(file)).descriptor
  const script = compileScript(descriptor, { id: 'closeout' })
  const template = compileTemplate({ source: descriptor.template.content, filename: file, id: 'closeout', compilerOptions: { bindingMetadata: script.bindings } })
  if (template.errors.length) throw template.errors[0]
  const code = template.code.replace(/import\s*{([^}]+)}\s*from\s*['"]vue['"];?/g, (_, names) => `const {${names.replace(/\bas\b/g, ':')}} = vue;`).replace('export function render', 'function render')
  const render = new Function('vue', `${code};return render`)(vue), scope = vue.proxyRefs(state)
  const app = vue.createSSRApp({ render: () => render(scope, [], {}, scope) })
  app.component('RouterLink', { setup: (_, { slots }) => () => vue.h('a', slots.default?.()) })
  return renderToString(app)
}
