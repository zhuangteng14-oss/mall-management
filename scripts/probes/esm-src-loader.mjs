// scripts/probes/esm-src-loader.mjs —— 只给探针用的**一步小手术**。
//
// 前端的源码是 `.js`，而 `mall-management-system/package.json` **没有** `"type": "module"`
// （它是 webpack 工程，模块系统由打包器决定）。于是 node 会把 `src/**/*.js` 按 CJS 解析，
// `import ... from 'vue'` 直接报 `Cannot use import statement outside a module`。
//
// 本 loader 把**前端 `src/` 下的 `.js`** 一律按 ESM 载入（其余文件原样交给 node）——
// 只在探针里注册（见各 `*.mjs` 顶部的 `module.register`），**不改** `package.json`，
// 也就不动构建配置（改那个会牵动整个 webpack 工程，为一个离线探针不值得）。
export async function load(url, context, nextLoad) {
  if (url.startsWith('file:') && url.endsWith('.js') && url.includes('/src/')) {
    const { readFile } = await import('node:fs/promises')
    return { format: 'module', source: await readFile(new URL(url), 'utf8'), shortCircuit: true }
  }
  return nextLoad(url, context)
}

/**
 * ⚠️ **第二条同样必要的补齐**：前端源码的相互 import **不写扩展名**
 * （`from '../config/labels/observe-labels'`）—— 那是 webpack 的解析口径，node 的 ESM 不认
 * （它要求写全 `.js`）。故这里只在「相对路径 + 没带扩展名 + 默认解析失败」时补一次 `.js`，
 * 其余一律走 node 自己的解析（不猜、不兜底别的后缀）。
 */
export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context)
  } catch (e) {
    if (specifier.startsWith('.') && !/\.[cm]?js$/.test(specifier)) {
      return nextResolve(`${specifier}.js`, context)
    }
    throw e
  }
}
