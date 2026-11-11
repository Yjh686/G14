import type { EChartsOption } from 'echarts'
import type { CallbackDataParams } from 'echarts/types/dist/shared'
import type { OptionStatsVO, RatingStatsVO, WordFreqVO } from '@/types/stat'

/**
 * 统计图表的 option 构造器。
 *
 * 单独抽成纯函数（不含组件、不碰 DOM）有两个好处：
 * 1. 可以写单元测试断言数字与文案，不必起浏览器；
 * 2. 统计页多处复用的配色/格式化只在这一处维护。
 */

/** 统一色板，和 Element Plus 主色系对齐 */
const PALETTE = [
  '#2f7fd1',
  '#67c23a',
  '#e6a23c',
  '#f56c6c',
  '#909399',
  '#8e6ee6',
  '#3ab8c2',
  '#d96fa8',
]

/** 0.4 → "40.0%" */
export function percent(ratio: number | null | undefined): string {
  if (ratio == null || Number.isNaN(ratio)) return '0%'
  return `${(ratio * 100).toFixed(1)}%`
}

/**
 * 选择题柱状图。横向条形：选项文本通常较长，横向不会挤成竖排。
 */
export function barOption(
  options: OptionStatsVO[],
  total: number,
): EChartsOption {
  // 倒序后 ECharts 的 yAxis 从下往上画，视觉上第一项在最上面
  const items = [...options].reverse()
  return {
    color: [PALETTE[0]!],
    // right 留 84px：条形末端要画 "N · XX.X%" 的标签。
    // 留 48px 时 ECharts 会按画布右界裁切，百分号被切掉（看起来像 37.5'）
    grid: { left: 8, right: 84, top: 12, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const arr = params as { name: string; value: number }[]
        if (!arr?.length) return ''
        const p = arr[0]!
        const ratio = total > 0 ? (p.value / total) * 100 : 0
        return `${p.name}<br/>${p.value} 人（${ratio.toFixed(1)}%）`
      },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f0f2f5' } },
      axisLabel: { color: '#909399' },
    },
    yAxis: {
      type: 'category',
      data: items.map((o) => o.content),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisLabel: { color: '#606266' },
    },
    series: [
      {
        type: 'bar',
        data: items.map((o) => o.count),
        barMaxWidth: 22,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          color: '#606266',
          formatter: (p: CallbackDataParams) => {
            const v = Number(p.value ?? 0)
            const ratio = total > 0 ? (v / total) * 100 : 0
            return `${v} · ${ratio.toFixed(1)}%`
          },
        },
      },
    ],
  }
}

/**
 * 选择题饼图。
 *
 * 多选题各选项占比相加会超过 100%，此时饼图在数学上不成立，
 * 但仍然有展示价值（看相对大小），所以调用方需自行决定是否显示。
 */
export function pieOption(options: OptionStatsVO[]): EChartsOption {
  return {
    color: PALETTE,
    tooltip: {
      trigger: 'item',
      formatter: (p: unknown) => {
        const item = p as { name: string; value: number; percent: number }
        return `${item.name}<br/>${item.value} 人（${item.percent}%）`
      },
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: '#606266', fontSize: 12 },
    },
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: options.map((o) => ({ name: o.content, value: o.count })),
      },
    ],
  }
}

/** 评分分布柱状图，x 轴是 1..ratingMax 星 */
export function ratingOption(rating: RatingStatsVO): EChartsOption {
  return {
    color: [PALETTE[2]!],
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const arr = params as { name: string; value: number }[]
        if (!arr?.length) return ''
        const p = arr[0]!
        return `${p.name}<br/>${p.value} 人`
      },
    },
    xAxis: {
      type: 'category',
      data: rating.distribution.map((d) => `${d.star} 星`),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisLabel: { color: '#606266' },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f0f2f5' } },
      axisLabel: { color: '#909399' },
    },
    series: [
      {
        type: 'bar',
        data: rating.distribution.map((d) => d.count),
        barMaxWidth: 36,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
        label: { show: true, position: 'top', color: '#606266' },
      },
    ],
  }
}

/**
 * 高频词横向条形图。
 *
 * 没做词云：词云需要额外注册一个插件，而且字体大小映射在
 * 中文短词上很难读；条形图能直接看出次数，也更好解释。
 */
export function wordOption(words: WordFreqVO[]): EChartsOption {
  const items = [...words].slice(0, 10).reverse()
  return {
    color: [PALETTE[5]!],
    grid: { left: 8, right: 40, top: 12, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const arr = params as { name: string; value: number }[]
        if (!arr?.length) return ''
        return `${arr[0]!.name}：${arr[0]!.value} 次`
      },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f0f2f5' } },
      axisLabel: { color: '#909399' },
    },
    yAxis: {
      type: 'category',
      data: items.map((w) => w.word),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisLabel: { color: '#606266' },
    },
    series: [
      {
        type: 'bar',
        data: items.map((w) => w.count),
        barMaxWidth: 16,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', color: '#606266' },
      },
    ],
  }
}
