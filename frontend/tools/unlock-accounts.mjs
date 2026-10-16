// 解锁演示账号（开发环境救援脚本）
//
// 为什么需要它：
//   后端有防爆破锁定 —— 连续 5 次密码错误后 `locked_until = now + 15min`，
//   在此期间**即使密码正确也会被拒**（HTTP 403 / code 40303「账号已锁定」）。
//   而管理员「重置密码」接口虽然会顺带清空失败计数与锁定，却要求先用管理员登录，
//   所以一旦 **admin 自己也被锁**，就形成了死锁：脚本救不了，只能直接改库。
//   本脚本就是那把钥匙。
//
// 用法：
//   node tools/unlock-accounts.mjs                # 解锁全部账号
//   node tools/unlock-accounts.mjs 2021001 admin  # 只解锁指定账号
//
// 连接参数默认与后端 src/main/resources/application.yml 一致，
// 可用环境变量覆盖：MYSQL_BIN / MYSQL_USER / MYSQL_PWD / MYSQL_DB。
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'

const DB = process.env.MYSQL_DB || 'class_survey'
const USER = process.env.MYSQL_USER || 'root'
const PWD = process.env.MYSQL_PWD || '123456'

const CANDIDATES = [
  process.env.MYSQL_BIN,
  'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe',
  'C:\\Program Files\\MySQL\\MySQL Server 5.7\\bin\\mysql.exe',
  'mysql'
].filter(Boolean)

const bin = CANDIDATES.find((p) => p === 'mysql' || existsSync(p))
if (!bin) {
  console.error('找不到 mysql 客户端。请设置环境变量 MYSQL_BIN 指向 mysql.exe。')
  process.exit(1)
}

const accounts = process.argv.slice(2).filter((a) => !a.startsWith('-'))

function run(sql) {
  return execFileSync(bin, ['-u', USER, '--default-character-set=utf8mb4', '-N', '-B', '-e', sql], {
    env: { ...process.env, MYSQL_PWD: PWD },
    encoding: 'utf8'
  }).trim()
}

const where =
  accounts.length > 0
    ? `WHERE account IN (${accounts.map((a) => `'${a.replace(/'/g, "''")}'`).join(', ')})`
    : ''

console.log(`mysql: ${bin}`)
console.log(`库:    ${DB}\n`)

const before = run(
  `SELECT account, login_fail_count, IFNULL(locked_until,'-') FROM ${DB}.sys_user ${where} ORDER BY id;`
)
console.log('=== 解锁前（账号 / 失败次数 / 锁定至） ===')
console.log(before || '(无匹配账号)')

run(`UPDATE ${DB}.sys_user SET login_fail_count = 0, locked_until = NULL ${where};`)

const after = run(
  `SELECT account, login_fail_count, IFNULL(locked_until,'-') FROM ${DB}.sys_user ${where} ORDER BY id;`
)
console.log('\n=== 解锁后 ===')
console.log(after || '(无匹配账号)')

console.log(
  '\n提示：解锁只是清掉「防爆破锁定」，不会改密码。\n' +
    '若密码也被测试脚本改过，接着跑：npm run seed -- --reset'
)
