<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowDown, Loading } from '@element-plus/icons-vue'
import { getTask, subscribeTask } from '../api/task'
import type { AgentStep, ReviewTask, Severity } from '../types/task'
import {
  SEVERITY_ORDER,
  SEVERITY_TAG,
  SEVERITY_TEXT,
  STATUS_TAG,
  STATUS_TEXT,
  fileName,
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
// 思考过程默认展开，但这一栏很长，留个开关让读者能先跳到问题清单
const traceOpen = ref(true)

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

// 模型自己读代码的全过程。历史任务没有这一步，所以整体判空
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
 * 模型给这次调用填的理由，来自工具参数里的 reason（必填）。轨迹里"它为什么要看这个文件"显示的就是这句。
 *
 * 为什么不显示模型自己的思考过程（`step.thought`）：thinking 模式下那部分是 reasoning_content，
 * 是模型的草稿纸，不是给人读的。实测它既不受提示词约束（写了"用中文"也照样出英文），
 * 内容也全是内部试错 —— 真实采样过一段：
 * "Let me grep by reading portions... I can't grep. Let me read the file size first — read 1-60 lines to see, then guess."
 * 把这句话摆在页面上，用户看到的是"它连工具都不会用"，而它其实只是没意识到自己没有 grep。
 * 理由参数是契约（必填、40 字以内、中文），可控；思考过程不是。
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
 * 时间轴每行只保留"找什么类 / 读什么文件"。
 * find_type 和 read_file 的 target 都是路径（find_type 返回的是解析出来的文件路径），所以统一取最后一段：
 * 查类显示类名、不带 .java，读文件显示文件名加行范围 ——
 * 大文件会被模型分成几段读，只显示文件名的话看起来像把同一个文件读了两次。
 * list_files 是例外：它的 target 是模型填的关键词（不是路径），直接原样显示成"列出 xxx"，
 * 这样在轨迹里能看出它是用什么词找到候选的。
 */
function stepLabel(step: AgentStep): string {
  if (!step.target) {
    return ''
  }
  if (step.toolName === 'list_files') {
    return `列出 ${step.target}`
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

/**
 * 显示模型当场填的理由（工具参数里的 reason，40 字以内）。
 * v3 之前的老数据没有 reason，这一轮的说明会是空的 —— 轨迹本身不受影响。
 */
function roundNote(group: RoundGroup): string {
  return group.reasons.join('；')
}


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
        <div class="section-title collapsible" @click="traceOpen = !traceOpen">
          <el-icon class="caret" :class="{ folded: !traceOpen }"><ArrowDown /></el-icon>
          思考过程
        </div>

        <div v-show="traceOpen">
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
              >
                {{ roundNote(group) }}
              </p>
            </el-timeline-item>
          </el-timeline>
        </div>

      </template>

      <template v-if="task.status !== 'FAILED' && !running">
        <el-descriptions :column="3" border size="small" class="stats">
          <el-descriptions-item label="问题数">{{ issues.length }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ formatSeconds(task.elapsedMillis) }}</el-descriptions-item>
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
            {{ SEVERITY_TEXT[severity] }} {{ severityCounts.get(severity) }}
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
                {{ SEVERITY_TEXT[row.severity as Severity] }}
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

/* 思考过程默认展开，但这一栏比问题清单长，点标题可以收起来 */
.collapsible {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  user-select: none;
}

.caret {
  transition: transform 0.2s;
}

.caret.folded {
  transform: rotate(-90deg);
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

.step-target {
  color: #606266;
  font-size: 12px;
  font-family: SFMono-Regular, Consolas, monospace;
  word-break: break-all;
}

/* 模型这一轮填的理由。写死在 40 字以内，两行足够；真有超长的也不会把版面撑开 */
.step-thought {
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
  margin: 12px 0 0;
}
</style>
