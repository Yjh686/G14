<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

/**
 * ECharts 容器。
 *
 * 只按需注册用到的图表与组件（柱状图、饼图），避免把整个 echarts
 * 打进包里——全量引入会让主 chunk 再多出好几百 KB。
 *
 * 用 shallowRef 存实例：echarts 实例内部有大量循环引用，
 * 用 ref 会被 Vue 深度代理，既慢又可能在 dispose 时出错。
 */
echarts.use([
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

const props = withDefaults(
  defineProps<{
    option: EChartsOption
    height?: string
    /** 图表内容为空时的占位文案，为空串则不显示占位 */
    emptyText?: string
    empty?: boolean
  }>(),
  { height: '260px', emptyText: '暂无数据', empty: false },
)

const el = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
let observer: ResizeObserver | null = null

function render() {
  if (!el.value) return
  if (props.empty) {
    chart.value?.clear()
    return
  }
  if (!chart.value) {
    chart.value = echarts.init(el.value)
  }
  // notMerge=true：题型切换时旧 series 不会残留
  chart.value.setOption(props.option, true)
  chart.value.resize()
}

onMounted(() => {
  render()
  // 侧边栏折叠、窗口缩放都会改变容器宽度，交给 ResizeObserver 统一处理
  if (el.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(el.value)
  }
})

watch(() => [props.option, props.empty], render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div class="chart-wrap" :style="{ height }">
    <div v-show="!empty" ref="el" class="chart-canvas" />
    <div v-if="empty" class="chart-empty">
      <el-empty :image-size="60" :description="emptyText" />
    </div>
  </div>
</template>

<style scoped>
.chart-wrap {
  position: relative;
  width: 100%;
}

.chart-canvas {
  width: 100%;
  height: 100%;
}

.chart-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
</style>
