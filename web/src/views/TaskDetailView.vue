<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { getTask, subscribeTask } from '../api/task'
import type { AgentStep, ReviewTask, Severity } from '../types/task'
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
  lineRange,
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

/**
 * 模型给这次调用填的理由，来自工具参数里的 reason（必填）。
 * 这是轨迹里"它为什么要看这个文件"的正文 —— 一句话、中文，比整段思考过程好读得多。
 */
function stepReason(step: AgentStep): string {
  if (!step.arguments) {
    return ''
  }
  try {
    const { reason } = JSON.parse(step.arguments) as { reason?: unknown }
    return typeof reason === 'string' ? reason.trim() : ''
  } catch {
    return ''
  }
}

/**
 * 老数据的兜底：v2 之前的轨迹没有 reason，只有模型整段的思考过程
 * （thinking 模式下是英文、几千字符）。最后一轮的 content 是报告本身，
 * 看起来像 JSON 的直接不显示，免得几 k token 的报告铺在时间轴上。
 */
function roundThought(step: AgentStep): string {
  const text = step.thought?.trim()
  return !text || text.startsWith('{') ? '' : text
}

/** 展开看全文的轮次。理由默认只留两行，太长的话点一下看全 */
const expandedRounds = ref<number[]>([])

function toggleThought(round: number) {
  expandedRounds.value = expandedRounds.value.includes(round)
    ? expandedRounds.value.filter((item) => item !== round)
    : [...expandedRounds.value, round]
}

/**
 * 时间轴每行只保留"找什么类 / 读什么文件"。
 * 两种工具的 target 都是路径（find_type 返回的是解析出来的文件路径），所以统一取最后一段：
 * 查类显示类名、不带 .java，读文件显示文件名加行范围 ——
 * 大文件会被模型分成几段读，只显示文件名的话看起来像把同一个文件读了两次。
 */
function stepLabel(step: AgentStep): string {
  if (!step.target) {
    return ''
  }
  const name = fileName(step.target)
  if (step.toolName !== 'read_file') {
    return name.replace(/\.java$/, '')
  }
  const range = lineRange(step.arguments)
  return range ? `${name}（${range}）` : name
}

interface RoundAction {
  toolName: string | null
  targets: string[]
}

interface RoundGroup {
  round: number
  /** toolName 为 null 表示这一轮没调工具、直接给出了结论 */
  final: boolean
  /** 这一轮每个工具调用各自填的中文理由 */
  reasons: string[]
  /** 没有 reason 的老数据，退回整段思考过程 */
  thought: string
  actions: RoundAction[]
}

/**
 * 整条轨迹里真正被读过的文件。
 * 查找类只是"为了读它"的中间步骤 —— 目标最终被读了，就不再单独显示这一条：
 * 否则时间轴上会一直是"查找类 Api"和"读取文件 Api.java"并列出现，看着像说了两遍。
 */
const readPaths = computed(() => new Set(
  steps.value
    .filter((step) => step.toolName === 'read_file' && step.target)
    .map((step) => step.target),
))

function mergedIntoRead(step: AgentStep): boolean {
  return step.toolName === 'find_type' && !!step.target && readPaths.value.has(step.target)
}

/**
 * 按轮聚合：模型一轮里可以并行发好几个工具调用（同一轮会有多条 step），
 * 合并成一行更贴近"这一轮它干了什么"的读法。
 * 同一轮里同一种工具的目标用顿号连起来；不同工具（比如同时查了类又读了文件）各占一段。
 * 被合并掉的查找如果让某一轮空掉了，那一轮直接不占行 —— 它只做了内部定位，没读任何代码。
 */
const rounds = computed<RoundGroup[]>(() => {
  const groups: RoundGroup[] = []
  for (const step of steps.value) {
    if (mergedIntoRead(step)) {
      continue
    }
    let group = groups[groups.length - 1]
    if (!group || group.round !== step.round) {
      group = {
        round: step.round,
        final: !step.toolName,
        reasons: [],
        thought: roundThought(step),
        actions: [],
      }
      groups.push(group)
    }
    const reason = stepReason(step)
    if (reason && !group.reasons.includes(reason)) {
      group.reasons.push(reason)
    }
    const label = stepLabel(step)
    let action = group.actions.find((item) => item.toolName === step.toolName)
    if (!action) {
      action = { toolName: step.toolName, targets: [] }
      group.actions.push(action)
    }
    // 同一轮里读了完全相同的行范围才去重；同一个文件的不同行段要各显示各的
    if (label && !action.targets.includes(label)) {
      action.targets.push(label)
    }
  }
  return groups.filter((group) => group.final || group.actions.length > 0)
})

/** 优先显示模型填的理由；老数据没有 reason 才退回整段思考过程 */
function roundNote(group: RoundGroup): string {
  return group.reasons.length ? group.reasons.join('；') : group.thought
}

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

      <!-- 轨迹是后端逐步累积推过来的，所以运行中就能看到已经跑完的每一步，不用等结束 -->
      <template v-if="steps.length">
        <div class="section-title">Agent 执行轨迹（模型自己决定读哪些代码）</div>

        <el-descriptions v-if="!running" :column="4" border size="small" class="stats">
          <el-descriptions-item label="总轮数">{{ agentRounds }}</el-descriptions-item>
          <el-descriptions-item label="工具调用">{{ toolCallCount }} 次</el-descriptions-item>
          <el-descriptions-item label="读过文件">{{ readFiles.length }} 个</el-descriptions-item>
          <el-descriptions-item label="整链路耗时">
            {{ formatSeconds(task.elapsedMillis) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-timeline class="timeline">
          <el-timeline-item
            v-for="group in rounds"
            :key="group.round"
            :type="group.final ? 'success' : 'primary'"
            :hollow="group.final"
            placement="top"
          >
            <div class="step-head">
              <span class="step-round">第 {{ group.round }} 轮</span>
              <template v-for="action in group.actions" :key="action.toolName ?? 'final'">
                <el-tag :type="action.toolName ? 'warning' : 'success'" size="small">
                  {{ toolLabel(action.toolName) }}
                </el-tag>
                <span v-if="action.targets.length" class="step-target">
                  {{ action.targets.join('、') }}
                </span>
              </template>
            </div>
            <p
              v-if="roundNote(group)"
              class="step-thought"
              :class="{ expanded: expandedRounds.includes(group.round) }"
              title="点击展开或收起"
              @click="toggleThought(group.round)"
            >
              {{ roundNote(group) }}
            </p>
          </el-timeline-item>
        </el-timeline>

        <p v-if="!running" class="hint">
          每一轮都是模型自己的决定：先看 diff 里不认识的名字，再决定去查哪个类、读哪个文件。
          查找类只是读取的前置步骤，所以只在"没读到代码"时才单独显示。
          每轮下面那段灰字是模型当场填的理由（工具参数里的 reason），点一下可以展开。
          轨迹里没出现的文件，说明它判断不需要看 —— 上下文不是我们事先备好的，而是它在循环里用出来的。
        </p>
      </template>

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

.step-target {
  color: #606266;
  font-size: 12px;
  font-family: SFMono-Regular, Consolas, monospace;
  word-break: break-all;
}

/* 模型这一轮的解释。默认只留两行，点一下看全文 */
.step-thought {
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
  cursor: pointer;
  white-space: pre-wrap;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.step-thought.expanded {
  display: block;
  -webkit-line-clamp: unset;
}

.hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
  margin: 12px 0 0;
}
</style>
