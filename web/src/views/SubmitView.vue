<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTask, previewRecall } from '../api/task'
import type { ContextStrategy, RecallPreview } from '../types/task'
import { compressionPercent, fileName, formatNumber, formatSeconds } from '../utils/display'
import { errorMessage } from '../utils/error'

const router = useRouter()

// 预填一个能跑的 commit，方便直接点「开始评审」试用
const form = reactive({
  repo: 'dumbbell5kg/coopwire',
  commitSha: '6f438275ba06710d71c708ed3e5d5eb7472bdbe8',
})

// 评审参数，默认值和后端 ReviewOptions 里的一致
const options = reactive({
  strategy: 'preload' as ContextStrategy,
  maxFiles: 12,
  totalBudget: 15000,
  maxRounds: 8,
})

const isAgent = computed(() => options.strategy === 'agent')

const paramSummary = computed(() =>
  isAgent.value
    ? `最多 ${options.maxRounds} 轮`
    : `${options.maxFiles} 个文件 / ${formatNumber(options.totalBudget)} 字符`,
)

const submitting = ref(false)
const previewing = ref(false)
const preview = ref<RecallPreview | null>(null)

// 换了仓库、commit 或参数，上一次的预览结果就过期了，留着会让人以为是这次的
watch(
  () => [
    form.repo,
    form.commitSha,
    options.strategy,
    options.maxFiles,
    options.totalBudget,
    options.maxRounds,
  ],
  () => {
    preview.value = null
  },
)

const previewPercent = computed(() =>
  preview.value ? compressionPercent(preview.value.contextChars, preview.value.rawChars) : 0,
)

function validate(): boolean {
  if (!form.repo.trim() || !form.commitSha.trim()) {
    ElMessage.warning('仓库和 commit 都要填')
    return false
  }
  return true
}

async function submit() {
  if (!validate()) return

  submitting.value = true
  try {
    const task = await createTask(form.repo.trim(), form.commitSha.trim(), { ...options })
    // 提交是毫秒级返回的，真正的评审在后台跑，跳到详情页看实时进度
    await router.push(`/tasks/${task.id}`)
  } catch (error) {
    ElMessage.error(`提交失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}

/** 只跑召回、不调模型，秒级返回且不产生费用，用来先确认参数合不合适 */
async function runPreview() {
  if (!validate()) return

  previewing.value = true
  try {
    preview.value = await previewRecall(form.repo.trim(), form.commitSha.trim(), { ...options })
  } catch (error) {
    ElMessage.error(`预览失败：${errorMessage(error)}`)
  } finally {
    previewing.value = false
  }
}
</script>

<template>
  <el-card class="card">
    <template #header>
      <span>发起一次代码评审</span>
    </template>

    <el-form label-width="110px" @submit.prevent>
      <el-form-item label="仓库">
        <el-input v-model="form.repo" placeholder="owner/repo 或 https://gitee.com/owner/repo" />
      </el-form-item>
      <el-form-item label="commit">
        <el-input v-model="form.commitSha" placeholder="commit 的完整 sha" />
      </el-form-item>

      <el-form-item label="上下文策略">
        <el-radio-group v-model="options.strategy">
          <el-radio-button value="preload">预塞式 · 符号召回</el-radio-button>
          <el-radio-button value="agent">Agent · 模型自主检索</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <p class="strategy-explain">
        <template v-if="isAgent">
          <b>Agent 式</b>：只给模型 diff 和两个工具（按类名查路径、按行号读文件），
          上下文由它自己一轮轮读出来。能追到 import 之外、藏在调用链里的引用，代价是轮数不可预测。
        </template>
        <template v-else>
          <b>预塞式</b>：先用符号索引算出 diff 引用到的类，把它们压缩成「骨架」一次性塞进提示词。
          一次调用、成本和耗时都可预算，代价是没被符号规则扫到的引用就永远补不上。
        </template>
      </p>

      <el-collapse class="advanced">
        <el-collapse-item name="params">
          <template #title>
            <span class="advanced-title">{{ isAgent ? 'Agent 参数' : 'RAG 召回参数' }}</span>
            <span class="advanced-current">{{ paramSummary }}</span>
          </template>

          <template v-if="isAgent">
            <el-form-item label="最多轮数">
              <el-input-number v-model="options.maxRounds" :min="1" :max="20" />
              <span class="unit">轮</span>
            </el-form-item>
            <p class="explain">
              模型每读一次代码算一轮。轮数用完它还没收尾的话，会被强制要求直接给结论 ——
              所以调小不一定省钱，可能只是让它少看几个文件。实测这个 commit 要 8 轮才够。
            </p>
          </template>

          <template v-else>
            <el-form-item label="最多召回">
              <el-input-number v-model="options.maxFiles" :min="1" :max="50" />
              <span class="unit">个文件</span>
            </el-form-item>
            <el-form-item label="字符预算">
              <el-input-number v-model="options.totalBudget" :min="1000" :max="200000" :step="1000" />
              <span class="unit">字符</span>
            </el-form-item>
            <p class="explain">
              RAG 的检索环节：diff 里只能看到改动处前后几行，看不到被引用类的定义。
              评审前会按符号索引把相关类的「骨架」（保留字段和方法签名、丢掉方法体）召回进来一起交给模型。
              <br />
              调大召回更全但更贵，调小省钱但可能漏掉关键上下文。改完先点「预览召回」看效果 ——
              这一步不调用模型，不花钱。
            </p>
          </template>
        </el-collapse-item>
      </el-collapse>

      <el-form-item class="actions">
        <el-button type="primary" :loading="submitting" @click="submit">开始评审</el-button>
        <el-button v-if="!isAgent" :loading="previewing" @click="runPreview">预览召回</el-button>
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon>
      <template v-if="isAgent">
        评审要跑 80~120 秒，提交后会自动跳到详情页，
        通过 SSE 实时看到「建索引 → 模型思考 → 读取代码」每一轮的进度，结束后能看到完整执行轨迹。
      </template>
      <template v-else>
        一次评审要跑 40~90 秒，提交后会自动跳到详情页，
        通过 SSE 实时看到「拉取 diff → 召回相关代码 → 模型评审 → 校验行号」的进度。
      </template>
    </el-alert>

    <div v-if="preview" class="preview">
      <div class="section-title">
        RAG 召回预览
        <el-tag size="small" class="tag">{{ preview.signature }}</el-tag>
      </div>

      <el-descriptions :column="5" border size="small" class="stats">
        <el-descriptions-item label="候选文件">{{ preview.candidateFiles }}</el-descriptions-item>
        <el-descriptions-item label="实际召回">{{ preview.files.length }}</el-descriptions-item>
        <el-descriptions-item label="召回字符">
          {{ formatNumber(preview.contextChars) }}
        </el-descriptions-item>
        <el-descriptions-item label="压缩率">{{ previewPercent }}%</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatSeconds(preview.elapsedMillis) }}</el-descriptions-item>
      </el-descriptions>

      <el-table :data="preview.files" size="small" style="width: 100%">
        <el-table-column label="文件" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">{{ fileName(row.path) }}</template>
        </el-table-column>
        <el-table-column label="路径" min-width="360" show-overflow-tooltip>
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
        候选是符号解析出来"有资格被召回"的文件，实际召回还要受上面两个参数限制。
        召回只读取代码，所以预览不花钱，也不会产生评审记录。
      </p>
    </div>
  </el-card>
</template>

<style scoped>
.card {
  max-width: 860px;
  margin: 0 auto;
}

.advanced {
  margin-bottom: 18px;
}

.advanced-title {
  font-weight: 600;
}

.advanced-current {
  color: #909399;
  font-size: 12px;
  margin-left: 10px;
}

.unit {
  color: #606266;
  font-size: 13px;
  margin-left: 8px;
}

.explain {
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
  margin: 0 0 4px;
}

.strategy-explain {
  color: #606266;
  font-size: 12px;
  line-height: 1.9;
  margin: 0 0 16px 110px;
}

.strategy-explain b {
  color: #303133;
}

.actions {
  margin-top: 18px;
}

.preview {
  margin-top: 20px;
  border-top: 1px solid #ebeef5;
  padding-top: 4px;
}

.section-title {
  font-weight: 600;
  margin: 16px 0 12px;
}

.tag {
  margin-left: 8px;
}

.stats {
  margin-bottom: 12px;
}

.path {
  color: #909399;
  font-size: 12px;
}

.hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.8;
  margin: 12px 0 0;
}
</style>
