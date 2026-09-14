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
  compressionPercent,
  fileName,
  formatCost,
  formatNumber,
  formatSeconds,
  formatTime,
  shortSha,
  sortBySeverity,
  toolLabel,
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

// 这次评审参考过的相关代码。历史任务和早期的纯 diff 评审没有这部分，所以整体判空
// agent 式的执行轨迹。预塞式评审没有这一步，所以整体判空
const steps = computed(() => task.value?.steps ?? [])
const toolCallCount = computed(() => steps.value.filter((step) => step.toolName).length)
const agentRounds = computed(() => (steps.value.length ? steps.value[steps.value.length - 1].round : 0))
const readFiles = computed(() => {
  const seen: string[] = []
  for (const step of steps.value) {
    if (step.toolName === 'read_file' && step.target && !seen.includes(step.target)) {
      seen.push(step.target)
    }
  }
  return seen
})

const contexts = computed(() => task.value?.contexts ?? [])
const recallChars = computed(() => contexts.value.reduce((sum, file) => sum + file.chars, 0))
const recallRawChars = computed(() => contexts.value.reduce((sum, file) => sum + file.rawChars, 0))

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

        <template v-if="steps.length">
          <div class="section-title">Agent 执行轨迹（模型自己决定读哪些代码）</div>

          <el-descriptions :column="4" border size="small" class="stats">
            <el-descriptions-item label="总轮数">{{ agentRounds }}</el-descriptions-item>
            <el-descriptions-item label="工具调用">{{ toolCallCount }} 次</el-descriptions-item>
            <el-descriptions-item label="读过文件">{{ readFiles.length }} 个</el-descriptions-item>
            <el-descriptions-item label="整链路耗时">
              {{ formatSeconds(task.elapsedMillis) }}
            </el-descriptions-item>
          </el-descriptions>

          <el-timeline class="timeline">
            <el-timeline-item
              v-for="(step, index) in steps"
              :key="index"
              :type="step.toolName ? 'primary' : 'success'"
              :hollow="!step.toolName"
              placement="top"
            >
              <div class="step-head">
                <span class="step-round">第 {{ step.round }} 轮</span>
                <el-tag :type="step.toolName ? 'warning' : 'success'" size="small">
                  {{ toolLabel(step.toolName) }}
                </el-tag>
                <span class="step-meta">
                  in {{ formatNumber(step.promptTokens) }} / out
                  {{ formatNumber(step.completionTokens) }} · {{ formatSeconds(step.elapsedMillis) }}
                </span>
              </div>
              <div v-if="step.target" class="step-target">{{ step.target }}</div>
              <p v-if="step.thought" class="step-thought">{{ step.thought }}</p>
              <pre v-if="step.resultSummary" class="step-result">{{ step.resultSummary }}</pre>
            </el-timeline-item>
          </el-timeline>

          <p class="hint">
            每一轮都是模型自己的决定：它先看到 diff 里不认识的名字，再决定去查哪个类、读哪个文件的哪几行。
            轨迹里没出现的文件，说明它判断不需要看。这正是 agent 式和预塞式最大的区别 ——
            上下文不是我们事先准备好的，而是它在循环里用出来的。
            in / out 是这一轮的输入输出 token（多轮之间重复的历史会命中缓存，所以实际计费比 in 显示的少）。
          </p>
        </template>

        <template v-if="contexts.length">
          <div class="section-title">本次 RAG 召回（模型评审时参考的相关代码）</div>

          <el-descriptions :column="4" border size="small" class="stats">
            <el-descriptions-item label="召回文件">{{ contexts.length }}</el-descriptions-item>
            <el-descriptions-item label="骨架字符">{{ formatNumber(recallChars) }}</el-descriptions-item>
            <el-descriptions-item label="原始字符">{{ formatNumber(recallRawChars) }}</el-descriptions-item>
            <el-descriptions-item label="压缩率">
              {{ compressionPercent(recallChars, recallRawChars) }}%
            </el-descriptions-item>
          </el-descriptions>

          <el-table :data="contexts" size="small" style="width: 100%">
            <el-table-column label="文件" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ fileName(row.path) }}</template>
            </el-table-column>
            <el-table-column label="路径" min-width="380" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="path">{{ row.path }}</span>
              </template>
            </el-table-column>
            <el-table-column label="骨架" width="100" align="right">
              <template #default="{ row }">{{ formatNumber(row.chars) }}</template>
            </el-table-column>
            <el-table-column label="原始" width="100" align="right">
              <template #default="{ row }">{{ formatNumber(row.rawChars) }}</template>
            </el-table-column>
            <el-table-column label="压缩率" width="90" align="right">
              <template #default="{ row }">{{ compressionPercent(row.chars, row.rawChars) }}%</template>
            </el-table-column>
          </el-table>

          <p class="hint">
            上面每一条结论都建立在这些上下文之上，所以单独列出来。
            召回的是「骨架」：保留字段和方法签名、丢掉方法体，用最小的体积让模型看到定义。
            这部分内容也计入了上面的 token 和费用。
          </p>
        </template>
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

.path {
  color: #909399;
  font-size: 12px;
}

.timeline {
  padding-left: 4px;
  margin-top: 4px;
}

.step-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.step-round {
  font-weight: 600;
  font-size: 13px;
}

.step-meta {
  color: #a8abb2;
  font-size: 12px;
  margin-left: auto;
}

.step-target {
  color: #606266;
  font-size: 12px;
  font-family: SFMono-Regular, Consolas, monospace;
  margin-top: 4px;
  word-break: break-all;
}

.step-thought {
  color: #303133;
  font-size: 13px;
  line-height: 1.7;
  margin: 6px 0 0;
  white-space: pre-wrap;
}

.step-result {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 8px 10px;
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  max-height: 160px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
}

.hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
  margin: 12px 0 0;
}
</style>
