<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTask } from '../api/task'
import { errorMessage } from '../utils/error'

const router = useRouter()

// 预填一个能跑的 commit，方便直接点「开始评审」演示
const form = reactive({
  repo: 'dumbbell5kg/coopwire',
  commitSha: '6f438275ba06710d71c708ed3e5d5eb7472bdbe8',
})
const submitting = ref(false)

async function submit() {
  if (!form.repo.trim() || !form.commitSha.trim()) {
    ElMessage.warning('仓库和 commit 都要填')
    return
  }

  submitting.value = true
  try {
    const task = await createTask(form.repo.trim(), form.commitSha.trim())
    if (task.status === 'SUCCESS') {
      // 新任务一定是 PENDING 起步，返回时就已经是终态，只可能是命中了缓存
      ElMessage.success('这个 commit 用同一版提示词评过了，直接复用历史结果')
    }
    // 提交是毫秒级返回的，真正的评审在后台跑，跳到详情页看实时进度
    await router.push(`/tasks/${task.id}`)
  } catch (error) {
    ElMessage.error(`提交失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
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
      <el-form-item>
        <el-button type="primary" :loading="submitting" @click="submit">开始评审</el-button>
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" show-icon>
      一次评审要跑 40~90 秒，提交后会自动跳到详情页，
      通过 SSE 实时看到「拉取 diff → 模型评审 → 校验行号」的进度。
    </el-alert>
  </el-card>
</template>

<style scoped>
.card {
  max-width: 720px;
  margin: 0 auto;
}
</style>
