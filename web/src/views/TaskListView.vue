<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getStats, listTasks } from '../api/task'
import type { ReviewStats, TaskSummary } from '../types/task'
import {
  STATUS_TAG,
  STATUS_TEXT,
  formatCost,
  formatNumber,
  formatSeconds,
  formatTime,
  shortSha,
} from '../utils/display'

const router = useRouter()
const tasks = ref<TaskSummary[]>([])
const stats = ref<ReviewStats | null>(null)
const loading = ref(false)

// 有任务在跑的时候才轮询列表。列表页不像详情页需要秒级反馈，
// 5 秒一次的普通轮询足够，没必要为它再开一条 SSE。
const hasRunning = computed(() =>
  tasks.value.some((task) => task.status === 'PENDING' || task.status === 'RUNNING'),
)
let timer: number | undefined

async function refresh() {
  loading.value = true
  try {
    tasks.value = await listTasks()
    schedule()
  } finally {
    loading.value = false
  }
  // 成本账单独查、单独失败：统计接口挂了不该把列表也带崩
  try {
    stats.value = await getStats()
  } catch {
    stats.value = null
  }
}

function schedule() {
  window.clearTimeout(timer)
  if (hasRunning.value) {
    timer = window.setTimeout(refresh, 5000)
  }
}

/** 一次评审的平均成本，用来估算缓存替我们省了多少钱 */
const avgCost = computed(() => {
  if (!stats.value || !stats.value.successCount) return 0
  return stats.value.totalCost / stats.value.successCount
})

const savedCost = computed(() => avgCost.value * (stats.value?.cacheHits ?? 0))

onUnmounted(() => window.clearTimeout(timer))

refresh()
</script>

<template>
  <div class="page">
    <div v-if="stats" class="cards">
      <div class="stat">
        <div class="label">评审任务</div>
        <div class="value">{{ stats.taskCount }}</div>
        <div class="sub">成功 {{ stats.successCount }}</div>
      </div>
      <div class="stat">
        <div class="label">累计花费</div>
        <div class="value">{{ formatCost(stats.totalCost) }}</div>
        <div class="sub">平均 {{ formatCost(avgCost) }}/次</div>
      </div>
      <div class="stat">
        <div class="label">平均耗时</div>
        <div class="value">{{ formatSeconds(stats.avgElapsedMillis) }}</div>
        <div class="sub">含拉取 diff</div>
      </div>
      <div class="stat">
        <div class="label">累计 token</div>
        <div class="value">{{ formatNumber(stats.totalTokens) }}</div>
        <div class="sub">输入 + 输出</div>
      </div>
      <div class="stat">
        <div class="label">发现问题</div>
        <div class="value">{{ stats.issueCount }}</div>
        <div class="sub">全部任务合计</div>
      </div>
      <div class="stat highlight">
        <div class="label">缓存命中</div>
        <div class="value">{{ stats.cacheHits }}</div>
        <div class="sub">约省下 {{ formatCost(savedCost) }}</div>
      </div>
    </div>

    <el-card>
      <template #header>
        <div class="header">
          <span>评审记录</span>
          <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
        </div>
      </template>

      <el-table
        :data="tasks"
        style="width: 100%"
        empty-text="还没有评审记录"
        @row-click="(row: TaskSummary) => router.push(`/tasks/${row.id}`)"
      >
        <el-table-column prop="id" label="任务" width="100" />
        <el-table-column prop="repo" label="仓库" min-width="180" show-overflow-tooltip />
        <el-table-column label="commit" width="110">
          <template #default="{ row }">{{ shortSha(row.commitSha) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status as keyof typeof STATUS_TAG]" size="small">
              {{ STATUS_TEXT[row.status as keyof typeof STATUS_TEXT] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="issueCount" label="问题数" width="90" />
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ formatSeconds(row.elapsedMillis) }}</template>
        </el-table-column>
        <el-table-column label="费用" width="110">
          <template #default="{ row }">{{ formatCost(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  max-width: 1200px;
  margin: 0 auto;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 14px 16px;
}

.stat.highlight {
  border-color: #b3e19d;
  background: #f0f9eb;
}

.label {
  color: #909399;
  font-size: 13px;
}

.value {
  font-size: 24px;
  font-weight: 600;
  margin: 6px 0 2px;
}

.sub {
  color: #909399;
  font-size: 12px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>
