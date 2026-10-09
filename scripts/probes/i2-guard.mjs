// §五·10 权限三档：闸开实例（8098, app.auth.enabled=true）上打 /notification/*
const BASE = 'http://127.0.0.1:8098'
const UN = 'i2probe'
const PW = 'i2probe'
const OWN = 'I2PROBE1'      // customerid（自己）
const OTHER = 'pIzTWafaGp'  // 另一个真实客户（不是自己）

let cookie = ''
const post = async (p, o = {}) => {
  const r = await fetch(BASE + p, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
      ...(cookie ? { Cookie: cookie } : {})
    },
    body: new URLSearchParams(o).toString()
  })
  const sc = r.headers.getSetCookie ? r.headers.getSetCookie() : []
  if (sc.length) cookie = sc.map(c => c.split(';')[0]).join('; ')
  const txt = await r.text()
  let j; try { j = JSON.parse(txt) } catch { j = { _raw: txt } }
  return { http: r.status, j }
}
const show = (t, o) => console.log(t.padEnd(44) + ' HTTP=' + o.http + '  ' + JSON.stringify(o.j).slice(0, 160))

show('[注册 loginAdd]', await post('/user/loginAdd', {
  username: UN, usertype: '客户', userid: UN, customerid: OWN,
  password: PW, age: 20, gender: '男'
}))
console.log('   cookie 拿到?', cookie ? '是' : '否（注册不建会话，正常）')
show('[登录 loginSelect]', await post('/user/loginSelect', { username: UN, password: PW }))
console.log('   cookie =', cookie || '(空)')
show('[身份 /user/me]', await post('/user/me'))
console.log('')
console.log('—— 三档判据 ——')
show('档1 传自己 toId（期望 200）', await post('/notification/unreadCount', { toId: OWN, toType: 'customer' }))
show('档3 传别人 toId（期望 403）', await post('/notification/unreadCount', { toId: OTHER, toType: 'customer' }))
show('档3 list 传别人（期望 403）', await post('/notification/list', { toId: OTHER, toType: 'customer' }))
show('档3 read 传别人（期望 403）', await post('/notification/read', { toId: OTHER, toType: 'customer', id: 1 }))
show('档3 readAll 传别人（期望 403）', await post('/notification/readAll', { toId: OTHER, toType: 'customer' }))
show('匿名（未带 cookie）—— 另起一轮', await (async () => { cookie = ''; return post('/notification/unreadCount', { toId: OTHER, toType: 'customer' }) })())
