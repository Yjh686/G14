/**
 * 只重截统计页（修完 chart.ts 的 grid 留白后验证标签不再被裁）。
 * 用法：node scripts/reshoot-stats.mjs [surveyId]
 */
import { chromium } from 'playwright-core'
import path from 'node:path'

const BASE = process.env.E2E_BASE ?? 'http://localhost:5173'
const CHROME = process.env.E2E_CHROME ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const OUT = path.resolve('..', '..', 'docs', '答辩素材')
const SURVEY_ID = Number(process.argv[2] ?? 224)

const browser = await chromium.launch({ executablePath: CHROME, headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
const page = await ctx.newPage()

async function login(u, p) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.clear())
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' })
  await page.fill('input[autocomplete="username"]', u)
  await page.fill('input[autocomplete="current-password"]', p)
  await page.click('button:has-text("登 录")')
  await page.waitForFunction(() => !!localStorage.getItem('g14_token'), { timeout: 15000 })
  await page.waitForTimeout(1000)
}

try {
  await login('teacher', 'Teacher@123')
  await page.goto(`${BASE}/surveys/${SURVEY_ID}/stats`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('canvas', { timeout: 15000 })
  await page.waitForTimeout(2000)
  await page.screenshot({ path: path.join(OUT, '04-统计分析-单选.png') })
  console.log('单选已重截')

  const nav = page.locator('.q-nav .q-nav-item, .q-nav li, .q-nav > div')
  const n = await nav.count()
  console.log('题目导航项', n)

  await nav.nth(1).click()
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(OUT, '05-统计分析-多选.png') })
  console.log('多选已重截')

  await nav.nth(4).click()
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(OUT, '06-统计分析-评分.png') })
  console.log('评分已重截')

  await nav.nth(3).click()
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(OUT, '06b-统计分析-文本.png') })
  console.log('文本已重截')
} finally {
  await browser.close()
}
