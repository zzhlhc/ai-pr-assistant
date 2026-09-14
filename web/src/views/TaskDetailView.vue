<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { getTask, subscribeTask } from '../api/task'
import type { ReviewTask, Severity } from '../types/task'
import {
  SEVERITY_ORDER,
  SEVERITY_TAG,
  STATUS_TAG,
  STATUS_TEXT,
  fileName,
  formatCost,
  formatSeconds,
  formatTime,
  shortSha,
  sortBySeverity,
} from '../utils/display'

const route = useRoute()

const task = ref<ReviewTask | null>(null)
const loadError = ref('')
const loading = ref(false)

let unsubscribe: (() => void) | undefined

const running = computed(() => task.value?.status === 'PENDING' || task.value?.status === 'RUNNING')
const issues = computed(() => sortBySeverity(task.value?.report?.issues ?? []))

const severityCounts = computed(() => {
  const counts = new Map<Severity, number>()
  for (const issue of issues.value) {
    counts.set(issue.severity, (counts.get(issue.severity) ?? 0) + 1)
  }
  return counts
})

async function load(id: string) {
  unsubscribe?.()
  task.value = null
  loadError.value = ''
  loading.value = true
  try {
    const loaded = await getTask(id)
    task.value = loaded
    // 已经结束的任务没必要再连 SSE，直接从库里读到的快照就够了
    if (loaded.status === 'PENDING' || loaded.status === 'RUNNING') {
      subscribe(id)
    }
  } catch (error) {
    loadError.value = String(error)
  } finally {
    loading.value = false
  }
}

function subscribe(id: string) {
  unsubscribe = subscribeTask(
    id,
    (next) => {
      task.value = next
      if (next.status === 'SUCCESS' || next.status === 'FAILED') {
        unsubscribe = undefined
      }
    },
    () => {
      // 推送断了就退化成一次普通查询，至少不会让页面一直停在旧状态
      unsubscribe?.()
      unsubscribe = undefined
      getTask(id)
        .then((latest) => {
          task.value = latest
        })
        .catch(() => undefined)
    },
  )
}

watch(() => route.params.id, (id) => load(String(id)), { immediate: true })
onUnmounted(() => unsubscribe?.())
</script>

<template>
  <div v-if="loadError">
    <el-alert type="error" :closable="false" show-icon :title="loadError" />
  </div>

  <div v-else v-loading="loading">
    <el-card v-if="task" class="card">
      <template #header>
        <div class="header">
          <div>
            <span class="repo">{{ task.repo }}</span>
            <span class="sha">@{{ shortSha(task.commitSha) }}</span>
            <el-tag :type="STATUS_TAG[task.status]" size="small" class="tag">
              {{ STATUS_TEXT[task.status] }}
            </el-tag>
          </div>
          <el-button size="small" @click="load(task.id)">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="task.error" type="error" :closable="false" show-icon :title="task.error" />

      <div v-if="running" class="running">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ task.stage }}…</span>
        <span class="hint">大模型评审通常要 40~90 秒，页面会自动更新</span>
      </div>

      <template v-if="task.status !== 'FAILED' && !running">
        <el-descriptions :column="5" border size="small" class="stats">
          <el-descriptions-item label="问题数">{{ issues.length }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatSeconds(task.elapsedMillis) }}</el-descriptions-item>
          <el-descriptions-item label="费用">{{ formatCost(task.usage?.cost) }}</el-descriptions-item>
          <el-descriptions-item label="token">
            {{ task.usage?.totalTokens ?? '-' }}
            <span v-if="task.usage?.cachedTokens" class="hint">
              （命中缓存 {{ task.usage.cachedTokens }}）
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ formatTime(task.finishedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="task.report?.summary" class="summary">
          <div class="section-title">总体评价</div>
          <p>{{ task.report.summary }}</p>
        </div>

        <div class="section-title">
          问题清单
          <el-tag
            v-for="severity in SEVERITY_ORDER"
            v-show="severityCounts.get(severity)"
            :key="severity"
            :type="SEVERITY_TAG[severity]"
            size="small"
            class="tag"
          >
            {{ severity }} {{ severityCounts.get(severity) }}
          </el-tag>
        </div>

        <el-table :data="issues" empty-text="这次改动没发现问题" style="width: 100%">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="detail">
                <div class="section-title">代码证据</div>
                <pre class="code">{{ row.evidence }}</pre>
                <div class="section-title">修改建议</div>
                <p>{{ row.suggestion }}</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="110">
            <template #default="{ row }">
              <el-tag :type="SEVERITY_TAG[row.severity as Severity]" size="small">
                {{ row.severity }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类型" width="110" />
          <el-table-column label="位置" width="240">
            <template #default="{ row }">
              {{ fileName(row.file) }}:{{ row.line }}
            </template>
          </el-table-column>
          <el-table-column prop="issue" label="问题" min-width="320" />
        </el-table>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.card {
  max-width: 1100px;
  margin: 0 auto;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.repo {
  font-weight: 600;
}

.sha {
  color: #909399;
  margin-left: 6px;
}

.tag {
  margin-left: 8px;
}

.running {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 24px 0;
  font-size: 15px;
}

.hint {
  color: #909399;
  font-size: 12px;
}

.stats {
  margin-bottom: 16px;
}

.summary {
  margin-bottom: 16px;
}

.summary p {
  line-height: 1.8;
  margin: 8px 0 0;
}

.section-title {
  font-weight: 600;
  margin: 16px 0 8px;
}

.detail {
  padding: 4px 16px 12px;
}

.code {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 10px 12px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}
</style>
