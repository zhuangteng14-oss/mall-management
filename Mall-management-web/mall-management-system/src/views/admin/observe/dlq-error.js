/**
 * 死信错误中文化（D3 手验反馈 · 2026-10-18）。
 *
 * 后端 `observed_event_dlq` 落的是**原始**异常类名 + 英文 message（`ObservedEventConsumer`
 * 的 `reportDeadLetters`）—— 那是排障要看的底稿，不能丢。管理员看不懂的是页面上那一列，
 * 所以在**渲染层**翻译：库里不动，旧行也一并生效，不需要数据迁移。
 *
 * 三类口径（与手验裁决逐字一致）：
 *   · 解析类  ⇒「【格式错误】消息体不是合法的 JSON 格式」
 *   · 数据库类 ⇒「【数据库错误】落库时发生异常」
 *   · 其它    ⇒「【系统异常】具体堆栈：{英文原文}」（原文照留，未知异常不假装看得懂）
 *
 * ⚠️ 判据同时看 `ErrorClass`（全限定类名）与 `FailedStage`（人话阶段）：
 *    `Sql.java` 的种子行、Jackson 的解析失败、Spring 的 `CannotAcquireLockException`
 *    都带有可识别的类名；万一某天类名缺失，阶段文案（「落库连续 3 次失败」/
 *    「消息不是合法的事件 JSON」）是第二道兜底。
 */

/** 解析类异常：Jackson 各分支 + 消息反序列化失败。 */
const FORMAT_CLASS = /jackson|JsonParse|JsonMapping|JsonProcessing|JsonEOF|JsonSyntax|MismatchedInput|UnrecognizedToken|HttpMessageNotReadable/i
/** 数据库类异常：JDBC 族 + Spring DAO 族（含死锁/锁等待/连接池/事务）。 */
const DB_CLASS = /SQLException|DataAccess|CannotAcquireLock|Deadlock|QueryTimeout|DataIntegrityViolation|DuplicateKey|CannotGetJdbcConnection|HikariPool|PessimisticLocking|CannotSerializeTransaction|UnexpectedRollback|TransactionSystem|MyBatis|Jdbc/i

const FORMAT_LABEL = '格式错误'
const DB_LABEL = '数据库错误'
const SYSTEM_LABEL = '系统异常'

/** 取 `ErrorClass` 的短类名（全限定名留给 `title`，排障时还能看到）。 */
export function dlqShortClass(row) {
  const raw = String((row && row.ErrorClass) || '')
  if (!raw) return ''
  const i = raw.lastIndexOf('.')
  return i >= 0 ? raw.slice(i + 1) : raw
}

/**
 * 归类。返回 `{ key, label }`；`key` 供样式/断言用，`label` 是「错误类型」列的中文。
 * 一条信息都没有时返回 `{ key: 'none', label: '—' }`。
 */
export function dlqErrorCategory(row) {
  const cls = String((row && row.ErrorClass) || '')
  const stage = String((row && row.FailedStage) || '')
  if (FORMAT_CLASS.test(cls) || /JSON|非法的事件/.test(stage)) {
    return { key: 'format', label: FORMAT_LABEL }
  }
  if (DB_CLASS.test(cls) || /落库|数据库|SQL/i.test(stage)) {
    return { key: 'db', label: DB_LABEL }
  }
  if (cls || stage || (row && row.ErrorText)) {
    return { key: 'system', label: SYSTEM_LABEL }
  }
  return { key: 'none', label: '—' }
}

/**
 * 「错误详情」列的中文文案。
 * 未知异常把英文原文缀在「具体堆栈：」之后 —— 这是裁决明确要求的，不截断；
 * 宽度由 CSS `word-break` 兜住。
 */
export function dlqErrorText(row) {
  const raw = String((row && row.ErrorText) || '').trim()
  const stage = String((row && row.FailedStage) || '').trim()
  const { key } = dlqErrorCategory(row)
  if (key === 'format') return '【格式错误】消息体不是合法的 JSON 格式'
  if (key === 'db') return '【数据库错误】落库时发生异常'
  if (key === 'system') return '【系统异常】具体堆栈：' + (raw || stage || '（无错误详情）')
  return '（无错误详情）'
}
