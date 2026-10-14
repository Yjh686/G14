// mock/dev-all.mjs —— 一条命令同时启动 mock 后端 + 前端开发服务器
// 用法：npm run dev:all
// 按 Ctrl+C 会同时关闭两个进程。
import { spawn } from 'node:child_process'

const procs = []
let shuttingDown = false

function start(label, cmd, args) {
  const child = spawn(cmd, args, { stdio: 'inherit', shell: true })
  procs.push(child)
  child.on('exit', (code) => {
    console.log(`[dev-all] ${label} 已退出（code=${code}）`)
    shutdown()
  })
}

function shutdown() {
  if (shuttingDown) return
  shuttingDown = true
  for (const p of procs) {
    try {
      p.kill()
    } catch {
      /* 忽略 */
    }
  }
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

start('mock后端', 'npm', ['run', 'mock'])
start('前端', 'npm', ['run', 'dev'])
