<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitReview } from './api/review'
import type { ReviewReport, Severity } from './types/review'

const form = ref({
  repo: 'dumbbell5kg/coopwire',
  commitSha: '6f438275ba06710d71c708ed3e5d5eb7472bdbe8',
})

const loading = ref(false)
/** 已等待秒数。模型一次要跑 40~90 秒，得让用户知道没卡死 */
const elapsed = ref(0)
const costMillis = ref(0)
const report = ref<ReviewReport | null>(null)

let timer: number | undefined

const severityType: Record<Severity, 'danger' | 'warning' | 'primary' | 'info'> = {
  CRITICAL: 'danger',
  MAJOR: 'warning',
  MINOR: 'primary',
  INFO: 'info',
}

const issues = computed(() => report.value?.issues ?? [])

const rowKey = (row: { file: string; line: number }) => `${row.file}:${row.line}`

async function onSubmit() {
  loading.value = true
  report.value = null
  elapsed.value = 0
  timer = window.setInterval(() => (elapsed.value += 1), 1000)
  const start = Date.now()

  try {
    report.value = await submitReview(form.value)
    costMillis.value = Date.now() - start
    ElMessage.success(`评审完成，发现 ${report.value.issues.length} 个问题`)
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message ?? e?.message ?? '评审失败')
  } finally {
    window.clearInterval(timer)
    loading.value = false
  }
}

onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <div class="page">
    <header class="hero">
      <h1>AI 代码评审</h1>
      <p>填 Gitee 仓库和 commit sha，模型读完整 diff 后给出结构化的评审意见</p>
    </header>

    <el-card shadow="never" class="card">
      <el-form :model="form" label-width="100px" @submit.prevent="onSubmit">
        <el-form-item label="仓库">
          <el-input v-model="form.repo" placeholder="owner/repo，例如 dumbbell5kg/coopwire" />
        </el-form-item>
        <el-form-item label="commit sha">
          <el-input v-model="form.commitSha" placeholder="40 位 commit sha" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="onSubmit">
            {{ loading ? `评审中… 已等待 ${elapsed} 秒` : '开始评审' }}
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="loading"
        type="info"
        show-icon
        :closable="false"
        :title="`模型正在读 diff 并推理，通常要 40~90 秒，别刷新（已等待 ${elapsed} 秒）`"
      />
    </el-card>

    <el-card v-if="report" shadow="never" class="card">
      <template #header>
        <div class="card-header">
          <span>评审报告</span>
          <span class="meta">
            耗时 {{ (costMillis / 1000).toFixed(1) }} 秒 · {{ issues.length }} 个问题
          </span>
        </div>
      </template>

      <el-alert type="success" :closable="false" :title="report.summary" class="summary" />

      <el-empty v-if="issues.length === 0" description="这次改动没发现问题" />

      <!-- row-key 用 file:line 组合，光用 line 会在跨文件同号时撞 key -->
      <el-table v-else :data="issues" stripe :row-key="rowKey">
        <!-- 展开行放 evidence 和修改建议，表格主体只留最关键的信息 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <pre class="evidence">{{ row.evidence }}</pre>
              <p class="suggestion"><strong>建议：</strong>{{ row.suggestion }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="110">
          <template #default="{ row }">
            <el-tag :type="severityType[row.severity as Severity]" effect="dark" size="small">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="110" />
        <el-table-column label="位置" width="280">
          <template #default="{ row }">
            <span class="loc">{{ row.file }}:{{ row.line }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="issue" label="问题" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 32px 20px 64px;
}

.hero h1 {
  margin: 0 0 8px;
  font-size: 26px;
}

.hero p {
  margin: 0 0 24px;
  color: #6b7280;
}

.card {
  margin-bottom: 20px;
  border-radius: 10px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.card-header .meta {
  font-weight: 400;
  font-size: 13px;
  color: #6b7280;
}

.summary {
  margin-bottom: 16px;
}

.detail {
  padding: 4px 8px 12px;
}

.evidence {
  margin: 0 0 12px;
  padding: 12px;
  background: #f6f8fa;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.suggestion {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
}

.loc {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
}
</style>
