import type { AxiosResponse } from 'axios'
import http, { get } from './http'
import type { QuestionStatsVO, SurveyStatsVO } from '@/types/stat'

/**
 * 统计与导出接口。后端路径 /api/surveys/{id}/stats 与 /api/surveys/{id}/export。
 *
 * 权限都是「自己 + 全部」这一对，故此处不区分；
 * 归属判定在后端 requireOwned 里做（教师只能看自己的问卷）。
 */
export const statApi = {
  /** stat:read:self | stat:read:all */
  survey: (surveyId: number) => get<SurveyStatsVO>(`/surveys/${surveyId}/stats`),

  /** 单题统计，用于切换题目时的局部刷新 */
  question: (surveyId: number, questionId: number) =>
    get<QuestionStatsVO>(`/surveys/${surveyId}/stats/questions/${questionId}`),
}

/* ---------------------------- 导出 ---------------------------- */

/** 从 Content-Disposition 里取后端给的文件名 */
function filenameFrom(disposition: string | undefined, fallback: string): string {
  if (!disposition) return fallback
  // 优先 RFC 5987 的 filename*=UTF-8''xxx，中文才不会变成下划线
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star?.[1]) {
    try {
      return decodeURIComponent(star[1])
    } catch {
      /* 解码失败就退回下面的分支 */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain?.[1] ?? fallback
}

/** 触发浏览器下载一个 Blob */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 立刻 revoke 在部分浏览器上会让下载中断，延后一点
  window.setTimeout(() => URL.revokeObjectURL(url), 2000)
}

/**
 * 导出问卷答卷为 xlsx，返回实际保存的文件名。
 *
 * 这是唯一不遵守 {code,msg,data} 约定的接口，所以不能用 get<T>：
 * http 的响应拦截器发现 body 里没有 code 就原样透传 AxiosResponse，
 * 因此这里拿到的是 AxiosResponse，文件名要从 Content-Disposition 里读，
 * 不能在前端自己拼（后端带时间戳且做过非法字符替换）。
 */
export async function exportAnswers(surveyId: number): Promise<string> {
  const res = await http.get(`/surveys/${surveyId}/export/answers`, {
    responseType: 'blob',
  })
  const axiosRes = res as unknown as AxiosResponse<Blob>
  const blob = axiosRes.data

  // 出错时后端仍返回 JSON（统一结构），此处把中文 msg 取出来抛给调用方
  if (blob.type.includes('json')) {
    const text = await blob.text()
    let msg = '导出失败'
    try {
      msg = (JSON.parse(text) as { msg?: string }).msg || msg
    } catch {
      /* 不是合法 JSON 就用默认文案 */
    }
    throw new Error(msg)
  }

  const name = filenameFrom(
    axiosRes.headers?.['content-disposition'] as string | undefined,
    `问卷答卷_${surveyId}.xlsx`,
  )
  saveBlob(blob, name)
  return name
}
