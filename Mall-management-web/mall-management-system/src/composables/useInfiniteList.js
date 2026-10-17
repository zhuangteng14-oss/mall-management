// useInfiniteList.js —— 商城端「下拉加载更多」的唯一实现（块 7 收尾 · P1）。
//
// 适用面：首页「为你推荐」、商品详情页「猜你喜欢」、搜索/类目页。它们共用同一条后端契约
// —— `limit` + `offset` 的**真数据库分页**（`ORDER BY Id DESC LIMIT ? OFFSET ?`），
// 前端只负责「滚到底就再要一页」。
//
// ⚠️ 三个刻意不做的决定（与 `components/Pager.vue` 的分工）：
//   ① **不存页码，只存 offset**：后端契约就是条数偏移，页码是派生量。存两份会分叉。
//   ② **不做「总数」**：商城端这几个端点不回 `total`（省掉每页一次 COUNT）。
//      终止判据是「这一页不满 pageSize」—— 满页时多发一次请求，代价可忽略。
//   ③ **不切数组**：`items` 就是累积结果，调用方直接渲染；需要去重 / 滤售完的面
//      自己包一层 computed（见 `HomePage.vue` 的 `sharedList` 用法）。
//
// ⚠️ IntersectionObserver 挂在**哨兵元素**上，不用滚动监听：滚动事件在高频列表里会
//    每帧触发，而 IO 由浏览器合成线程统一调度；且哨兵被 `v-if` 摘掉/换回来时，
//    `watch(sentinel)` 会重新挂 —— 这是 `onMounted` 一次性抓 ref 做不到的。
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * @param {(offset:number, limit:number) => Promise<{rows:Array, maybeMore?:boolean}>} fetchPage
 *        取一页。**必须**按后端回的数组长度推进 offset（不要自己加 limit：后端可能夹小）。
 * @param {number} pageSize 每页条数（商城端默认 24）。必须与请求里发出去的数字**逐字相同**。
 */
export function useInfiniteList(fetchPage, pageSize = 24) {
  const items = ref([])
  const loading = ref(false)
  const done = ref(false)
  const errMsg = ref('')
  const sentinel = ref(null)

  let offset = 0
  /** 代际号：`reset()` 之后，旧请求的返回一律丢弃（筛选条件变了而旧页后到 ⇒ 串味）。 */
  let generation = 0
  let observer = null

  async function loadNext() {
    if (loading.value || done.value || errMsg.value) return false
    const gen = generation
    loading.value = true
    try {
      const { rows, maybeMore } = await fetchPage(offset, pageSize)
      if (gen !== generation) return false
      const list = Array.isArray(rows) ? rows : []
      items.value = items.value.concat(list)
      offset += list.length
      // ⚠️ 两种终止信号：后端明确说「没有了」（maybeMore === false），或本页不满。
      if (maybeMore === false || list.length < pageSize) done.value = true
      return list.length > 0
    } catch (e) {
      if (gen === generation) {
        errMsg.value = (e && e.message) || '加载失败'
      }
      return false
    } finally {
      if (gen === generation) loading.value = false
      // ⚠️ 内容不足一屏时哨兵仍停在视口里，IO 不会**再**触发一次 ⇒ 这里主动续一脚。
      //    只续一次（下一轮 loadNext 自己也会走到这里），不会死循环：done 后会停。
      if (gen === generation && !done.value && !errMsg.value && sentinel.value) {
        const rect = sentinel.value.getBoundingClientRect()
        if (rect.top < window.innerHeight + 120) {
          // 放到下一帧，避免同一调用栈里递归。
          requestAnimationFrame(() => { loadNext() })
        }
      }
    }
  }

  /** 从头再来（切换筛选 / 重新搜索时调用）。旧代际的在途响应会被丢弃。 */
  function reset() {
    generation += 1
    offset = 0
    items.value = []
    done.value = false
    errMsg.value = ''
    loading.value = false
  }

  function retry() {
    errMsg.value = ''
    loadNext()
  }

  function bindObserver(el) {
    if (observer) {
      observer.disconnect()
      observer = null
    }
    if (!el || typeof IntersectionObserver === 'undefined') return
    observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) loadNext()
    }, { rootMargin: '240px 0px' })
    observer.observe(el)
  }

  watch(sentinel, (el) => bindObserver(el))

  onBeforeUnmount(() => {
    generation += 1
    if (observer) observer.disconnect()
    observer = null
  })

  return { items, loading, done, errMsg, sentinel, loadNext, reset, retry }
}
