import { describe, expect, it } from 'vitest'
import { barOption, percent, pieOption, ratingOption, wordOption } from '@/utils/chart'
import type { OptionStatsVO, RatingStatsVO } from '@/types/stat'

/**
 * 图表 option 是纯数据，可以直接断言。
 * 这里只断言「数字与文案对不对」，不断言像素——
 * 像素级回归交给 e2e-stat.mjs 的 canvas 存在性检查。
 */

const opts: OptionStatsVO[] = [
  { optionId: 1, content: '大一', count: 2, ratio: 0.4 },
  { optionId: 2, content: '大二', count: 2, ratio: 0.4 },
  { optionId: 3, content: '大三', count: 1, ratio: 0.2 },
]

describe('percent', () => {
  it('把 0~1 转成一位小数百分比', () => {
    expect(percent(0.4)).toBe('40.0%')
    expect(percent(1)).toBe('100.0%')
  })

  it('空值与 NaN 都退化成 0%，不显示 NaN', () => {
    expect(percent(null)).toBe('0%')
    expect(percent(undefined)).toBe('0%')
    expect(percent(Number.NaN)).toBe('0%')
  })
})

describe('barOption', () => {
  it('y 轴倒序，第一项在视觉最上方', () => {
    const o = barOption(opts, 5)
    expect((o.yAxis as { data: string[] }).data).toEqual(['大三', '大二', '大一'])
  })

  it('柱子数值与传入一致', () => {
    const o = barOption(opts, 5)
    const series = o.series as { data: number[] }[]
    expect(series[0]!.data).toEqual([1, 2, 2])
  })

  it('分母为 0 时标签显示 0 而不是 NaN', () => {
    const o = barOption(
      [{ optionId: 1, content: 'A', count: 0, ratio: 0 }],
      0,
    )
    const series = o.series as {
      label: { formatter: (p: { value: number }) => string }
    }[]
    expect(series[0]!.label.formatter({ value: 0 })).toBe('0 · 0.0%')
  })
})

describe('pieOption', () => {
  it('每个选项一个扇区，名称用选项文本', () => {
    const o = pieOption(opts)
    const series = o.series as { data: { name: string; value: number }[] }[]
    expect(series[0]!.data).toEqual([
      { name: '大一', value: 2 },
      { name: '大二', value: 2 },
      { name: '大三', value: 1 },
    ])
  })
})

describe('ratingOption', () => {
  it('x 轴按 1..ratingMax 全档给齐，没被选过的档也在', () => {
    const rating: RatingStatsVO = {
      answeredCount: 5,
      average: 4.2,
      ratingMax: 5,
      distribution: [
        { star: 1, count: 0, ratio: 0 },
        { star: 2, count: 0, ratio: 0 },
        { star: 3, count: 1, ratio: 0.2 },
        { star: 4, count: 2, ratio: 0.4 },
        { star: 5, count: 2, ratio: 0.4 },
      ],
    }
    const o = ratingOption(rating)
    expect((o.xAxis as { data: string[] }).data).toEqual([
      '1 星',
      '2 星',
      '3 星',
      '4 星',
      '5 星',
    ])
    const series = o.series as { data: number[] }[]
    expect(series[0]!.data).toEqual([0, 0, 1, 2, 2])
  })
})

describe('wordOption', () => {
  it('最多取前 10 个词，且倒序让最高频在最上', () => {
    const words = Array.from({ length: 12 }, (_, i) => ({
      word: `词${i}`,
      count: 12 - i,
    }))
    const o = wordOption(words)
    const data = (o.yAxis as { data: string[] }).data
    expect(data).toHaveLength(10)
    // 原数组第 10 个（count 最小）在最上方
    expect(data[0]).toBe('词9')
    expect(data[9]).toBe('词0')
  })
})
