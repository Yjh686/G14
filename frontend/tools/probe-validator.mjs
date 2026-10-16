// 校验器探测：直接复用前端真实模块 src/utils/validate.js，
// 确认后端真实账号都能通过前端校验（即请求发得出去）。
import { accountValidatorByRole } from '../src/utils/validate.js'

function run(role, value) {
  return new Promise((resolve) => {
    accountValidatorByRole(role)(null, value, (err) => resolve(err ? err.message : null))
  })
}

const cases = [
  ['TEACHER', 'T1001'],
  ['TEACHER', 'T1002'],
  ['TEACHER', '13800000001'],
  ['TEACHER', '13900000001'],
  ['STUDENT', '2021001'],
  ['STUDENT', '2021002'],
  ['ADMIN', 'admin'],
  ['STUDENT', 'T1001'],
  ['', ''],
  ['STUDENT', 'ab'],
  ['STUDENT', 'x'.repeat(33)],
  ['STUDENT', 'bad account']
]

const pad = (s, n) => String(s).padEnd(n)
let blocked = 0

for (const [role, acct] of cases) {
  const err = await run(role, acct)
  const label = acct === '' ? '(empty)' : acct
  if (err) blocked++
  console.log('  role=' + pad(role || '-', 8) + ' account=' + pad(label, 34) + ' -> ' + (err ? 'BLOCKED: ' + err : 'OK'))
}

// 关键回归：后端真实存在的账号必须全部放行
const mustPass = [
  ['TEACHER', 'T1001'],
  ['TEACHER', 'T1002'],
  ['TEACHER', '13800000001'],
  ['STUDENT', '2021001'],
  ['STUDENT', '2021002'],
  ['ADMIN', 'admin']
]
let regressions = 0
for (const [role, acct] of mustPass) {
  if (await run(role, acct)) {
    regressions++
    console.log('  [REGRESSION] ' + role + ' ' + acct + ' is blocked!')
  }
}

console.log('')
console.log('blocked total = ' + blocked + ' (expected: empty/short/long/illegal only)')
console.log('regressions  = ' + regressions + ' (must be 0)')
process.exit(regressions ? 1 : 0)
