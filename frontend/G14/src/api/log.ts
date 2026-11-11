import type { AxiosResponse } from 'axios'
import http, { get } from './http'
import type { LoginLogVO, LogQuery, OperationLogVO } from '@/types/log'
import type { PageResult } from '@/types/api'

/**
 * 日志查询接口（FR-LOG-01/02/03）。
 *
 * 全部为 GET：日志只读，管理员也不能删改（要清只能 DBA 直连数据库，
 * 那样任何删改都留得下痕迹）。
 */

/** 把查询条件里的空串去掉，避免传 `keyword=` 让后端拼出 `like '%%'` 的多余分支 */
function clean(q: LogQuery): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  Object.entries(q).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = v
  })
  return out
}

export const logApi = {
  /** log:login:read */
  loginLogs: (q: LogQuery) => get<PageResult<LoginLogVO>>('/logs/login', clean(q)),

  /** log:operation:read */
  operationLogs: (q: LogQuery) => get<PageResult<OperationLogVO>>('/logs/operations', clean(q)),

  /** 操作日志里出现过的模块名，用于筛选下拉（不写死在前端，后端新增模块自动出现） */
  modules: () => get<string[]>('/logs/modules'),
}

/* ---------------------------- 导出 ---------------------------- */

/** 从 Content-Disposition 取文件名（优先 RFC 5987，中文才不乱码） */
function filenameFrom(disposition: string | undefined, fallback: string): string {
  if (!disposition) return fallback
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1])
    } catch {
      /* 落回下面的分支 */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] ?? fallback
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/**
 * 导出日志为 xlsx。与答卷导出同一套路：不能用 get<T>，
 * 因为该接口返回的是文件流而不是 {code,msg,data}。
 */
async function exportLogs(path: string, q: LogQuery, fallback: string): Promise<string> {
  const res = await http.get(path, { params: clean(q), responseType: 'blob' })
  const axiosRes = res as unknown as AxiosResponse<Blob>
  const blob = axiosRes.data

  if (blob.type.includes('json')) {
    const text = await blob.text()
    let msg = '导出失败'
    try {
      msg = (JSON.parse(text) as { msg?: string }).msg || msg
    } catch {
      /* 非 JSON 用默认文案 */
    }
    throw new Error(msg)
  }

  const name = filenameFrom(
    axiosRes.headers?.['content-disposition'] as string | undefined,
    fallback,
  )
  saveBlob(blob, name)
  return name
}

/** log:export */
export function exportLoginLogs(q: LogQuery): Promise<string> {
  return exportLogs('/logs/login/export', q, '登录日志.xlsx')
}

/** log:export */
export function exportOperationLogs(q: LogQuery): Promise<string> {
  return exportLogs('/logs/operations/export', q, '操作日志.xlsx')
}
