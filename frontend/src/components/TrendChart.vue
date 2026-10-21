<script lang="ts">
export type TrendSeries = { name: string; color: string; values: (number | null)[] }
</script>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    dates: string[]
    bars?: TrendSeries[]
    lines?: TrendSeries[]
    formatValue?: (value: number) => string
  }>(),
  {
    bars: () => [],
    lines: () => [],
    formatValue: (value: number) => String(value),
  },
)

const WIDTH = 600
const HEIGHT = 180
const PAD_TOP = 22
const PAD_RIGHT = 12
const PAD_BOTTOM = 28
const PAD_LEFT = 12
const plotWidth = WIDTH - PAD_LEFT - PAD_RIGHT
const plotHeight = HEIGHT - PAD_TOP - PAD_BOTTOM

const range = computed(() => {
  const values: number[] = []
  for (const series of [...props.bars, ...props.lines]) {
    for (const value of series.values) if (value !== null) values.push(value)
  }
  if (!values.length) return { min: 0, max: 1 }
  let min = Math.min(...values)
  let max = Math.max(...values)
  if (props.bars.length > 0) min = Math.min(0, min)
  if (min === max) {
    min -= 1
    max += 1
  }
  return { min, max }
})

function x(index: number): number {
  const count = props.dates.length
  if (count <= 1) return PAD_LEFT + plotWidth / 2
  return PAD_LEFT + (index / (count - 1)) * plotWidth
}

function y(value: number): number {
  const { min, max } = range.value
  return PAD_TOP + (1 - (value - min) / (max - min)) * plotHeight
}

type BarRect = {
  x: number
  y: number
  width: number
  height: number
  date: string
  name: string
  color: string
  value: number
}

const barRects = computed<BarRect[]>(() => {
  const count = props.dates.length
  if (!count || !props.bars.length) return []
  const slot = plotWidth / count
  const width = Math.min(10, (slot * 0.7) / props.bars.length)
  const baseY = y(Math.max(0, range.value.min))
  const rects: BarRect[] = []
  props.bars.forEach((series, seriesIndex) => {
    series.values.forEach((value, index) => {
      if (value === null) return
      const cx = x(index) + (seriesIndex - (props.bars.length - 1) / 2) * (width + 1)
      rects.push({
        x: cx - width / 2,
        y: Math.min(y(value), baseY),
        width,
        height: Math.max(1, Math.abs(baseY - y(value))),
        date: props.dates[index],
        name: series.name,
        color: series.color,
        value,
      })
    })
  })
  return rects
})

type LinePoint = { cx: number; cy: number; date: string; value: number }
type LineShape = { name: string; color: string; path: string; points: LinePoint[] }

// null 值断开折线，只连接有值的连续点。
const lineShapes = computed<LineShape[]>(() =>
  props.lines.map((series) => {
    const segments: string[] = []
    const points: LinePoint[] = []
    let pen = false
    series.values.forEach((value, index) => {
      if (value === null) {
        pen = false
        return
      }
      const cx = x(index)
      const cy = y(value)
      segments.push(`${pen ? 'L' : 'M'}${cx.toFixed(1)},${cy.toFixed(1)}`)
      pen = true
      points.push({ cx, cy, date: props.dates[index], value })
    })
    return { name: series.name, color: series.color, path: segments.join(' '), points }
  }),
)

const zeroLineY = computed(() => (range.value.min < 0 ? y(0) : null))
const legend = computed(() => [...props.bars, ...props.lines])
const startDate = computed(() => props.dates[0] ?? '')
const endDate = computed(() => props.dates[props.dates.length - 1] ?? '')
</script>

<template>
  <section class="trend-chart">
    <div class="trend-chart-header">
      <h4>{{ title }}</h4>
      <div class="trend-chart-legend">
        <span v-for="series in legend" :key="series.name" class="trend-chart-legend-item"
          ><i class="trend-chart-legend-swatch" :style="{ background: series.color }" />{{
            series.name
          }}</span
        >
      </div>
    </div>
    <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" role="img" :aria-label="title" class="trend-chart-svg">
      <text class="trend-axis-label" :x="PAD_LEFT" :y="13">{{ formatValue(range.max) }}</text>
      <text class="trend-axis-label" :x="PAD_LEFT" :y="PAD_TOP + plotHeight - 2">
        {{ formatValue(range.min) }}
      </text>
      <text class="trend-axis-label" :x="PAD_LEFT" :y="HEIGHT - 6">{{ startDate }}</text>
      <text class="trend-axis-label trend-axis-label--end" :x="WIDTH - PAD_RIGHT" :y="HEIGHT - 6">
        {{ endDate }}
      </text>
      <line
        v-if="zeroLineY !== null"
        :x1="PAD_LEFT"
        :x2="WIDTH - PAD_RIGHT"
        :y1="zeroLineY"
        :y2="zeroLineY"
        class="trend-zero-line"
      />
      <rect
        v-for="(rect, index) in barRects"
        :key="`bar-${index}`"
        :x="rect.x"
        :y="rect.y"
        :width="rect.width"
        :height="rect.height"
        :fill="rect.color"
      >
        <title>{{ rect.date }} {{ rect.name }}：{{ formatValue(rect.value) }}</title>
      </rect>
      <path
        v-for="shape in lineShapes"
        :key="`line-${shape.name}`"
        :d="shape.path"
        :stroke="shape.color"
        class="trend-line"
      />
      <g v-for="shape in lineShapes" :key="`dots-${shape.name}`">
        <circle
          v-for="point in shape.points"
          :key="`${shape.name}-${point.date}`"
          :cx="point.cx"
          :cy="point.cy"
          r="2.5"
          :fill="shape.color"
        >
          <title>{{ point.date }} {{ shape.name }}：{{ formatValue(point.value) }}</title>
        </circle>
      </g>
    </svg>
  </section>
</template>

<style scoped>
.trend-chart {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trend-chart-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.trend-chart-header h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.trend-chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.trend-chart-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-secondary);
}
.trend-chart-legend-swatch {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.trend-chart-svg {
  display: block;
  width: 100%;
  height: auto;
}
.trend-axis-label {
  font-size: 10px;
  fill: var(--text-muted);
}
.trend-axis-label--end {
  text-anchor: end;
}
.trend-zero-line {
  stroke: var(--border-color);
  stroke-dasharray: 4 3;
  stroke-width: 1;
}
.trend-line {
  fill: none;
  stroke-width: 1.6;
}
</style>
