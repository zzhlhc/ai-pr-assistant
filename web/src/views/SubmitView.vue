<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createTask } from '../api/task'
import { errorMessage } from '../utils/error'

const router = useRouter()

// 预填一个能跑的 commit，方便直接点「开始评审」试用
const form = reactive({
  repo: 'dumbbell5kg/coopwire',
  commitSha: '6f438275ba06710d71c708ed3e5d5eb7472bdbe8',
})

const submitting = ref(false)

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
    const task = await createTask(form.repo.trim(), form.commitSha.trim())
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

      <el-form-item class="actions">
        <el-button type="primary" :loading="submitting" @click="submit">开始评审</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.card {
  max-width: 860px;
  margin: 0 auto;
}

.actions {
  margin-top: 18px;
}
</style>
