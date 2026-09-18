<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listCommits, listRepos } from '../api/gitee'
import { createTask } from '../api/task'
import type { GiteeCommitSummary, RepoOption } from '../types/gitee'
import { errorMessage } from '../utils/error'

const router = useRouter()

const form = reactive({ repo: '', commitSha: '' })
const repos = ref<RepoOption[]>([])
const shownRepos = ref<RepoOption[]>([])
const commitOptions = ref<GiteeCommitSummary[]>([])
const loadingRepos = ref(false)
const loadingCommits = ref(false)
const submitting = ref(false)

/**
 * 过滤完全交给我们自己做：一旦传了 filter-method，el-select 就不再按 label 过滤选项了，
 * 于是可以把显示名、真实路径、原仓库路径、描述一起匹配 —— 输入"芋道"这种中文也能命中。
 * 显示的是原项目名（若依/RuoYi），所以这里也匹配 fullName 和原路径，两边都搜得到。
 */
function filterRepos(query: string) {
  const q = query.trim().toLowerCase()
  shownRepos.value = q
    ? repos.value.filter((item) =>
        [item.displayName, item.fullName, item.sourceFullName, item.description].some(
          (field) => field && field.toLowerCase().includes(q),
        ),
      )
    : repos.value
}

/** 手输时允许直接贴地址，这里统一成 owner/repo，否则 commit 列表的接口拼不出来 */
function normalizeRepo(input: string): string {
  return input
    .trim()
    .replace(/^git@gitee\.com:/, '')
    .replace(/^https?:\/\/gitee\.com\//, '')
    .replace(/\.git$/, '')
    .replace(/^\/+|\/+$/g, '')
}

/** 换仓库就得换 commit：上一个仓库的 sha 在新仓库里根本不存在 */
async function onRepoChange(input: string) {
  const repo = normalizeRepo(input)
  form.repo = repo
  form.commitSha = ''
  commitOptions.value = []
  if (!repo.includes('/')) return

  loadingCommits.value = true
  try {
    commitOptions.value = await listCommits(repo)
    // 默认选中最新那个，少点一步
    if (commitOptions.value.length > 0) {
      form.commitSha = commitOptions.value[0].sha
    }
  } catch (error) {
    ElMessage.error(`拉取 commit 失败：${errorMessage(error)}`)
  } finally {
    loadingCommits.value = false
  }
}

/** commit 的 message 常常是多行的，下拉框里只留第一行 */
function commitLabel(item: GiteeCommitSummary): string {
  const message = item.commit?.message.split('\n')[0] ?? ''
  return `${message}（${item.sha.slice(0, 7)}）`
}

function commitTime(item: GiteeCommitSummary): string {
  const date = item.commit?.author?.date
  return date ? new Date(date).toLocaleString('zh-CN', { hour12: false }) : ''
}

function validate(): boolean {
  if (!form.repo.trim() || !form.commitSha.trim()) {
    ElMessage.warning('仓库和 commit 都要选')
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

onMounted(async () => {
  loadingRepos.value = true
  try {
    repos.value = await listRepos()
    shownRepos.value = repos.value
    // 默认挑最热门的那个，打开页面就能直接点「开始评审」
    if (repos.value.length > 0) {
      await onRepoChange(repos.value[0].fullName)
    }
  } catch (error) {
    ElMessage.error(`加载仓库清单失败：${errorMessage(error)}`)
  } finally {
    loadingRepos.value = false
  }
})
</script>

<template>
  <el-card class="card">
    <template #header>
      <span>发起一次代码评审</span>
    </template>

    <el-form label-width="110px" @submit.prevent>
      <el-form-item label="仓库">
        <el-select
          v-model="form.repo"
          filterable
          default-first-option
          :filter-method="filterRepos"
          :loading="loadingRepos"
          placeholder="请选择Gitee热门项目"
          class="picker"
          @change="onRepoChange"
        >
          <el-option
            v-for="item in shownRepos"
            :key="item.fullName"
            :label="item.displayName"
            :value="item.fullName"
          >
            <span class="repo-option">
              <span class="repo-name">{{ item.displayName }}</span>
              <span class="repo-desc">{{ item.description }}</span>
              <span v-if="item.stars" class="repo-stars">{{ item.stars }} ★</span>
            </span>
          </el-option>
        </el-select>
      </el-form-item>

      <el-form-item label="commit">
        <el-select
          v-model="form.commitSha"
          :loading="loadingCommits"
          placeholder="选择要评审的 commit"
          class="picker"
        >
          <el-option
            v-for="item in commitOptions"
            :key="item.sha"
            :label="commitLabel(item)"
            :value="item.sha"
          >
            <span class="repo-option">
              <span class="commit-message">{{ commitLabel(item) }}</span>
              <span class="commit-time">{{ commitTime(item) }}</span>
            </span>
          </el-option>
        </el-select>
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

.picker {
  width: 100%;
}

.repo-option {
  display: flex;
  align-items: center;
  gap: 10px;
}

.repo-name {
  flex: none;
}

/* 次要信息一律小字灰色，被挤的时候先牺牲描述 */
.repo-desc,
.commit-time,
.repo-stars {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.repo-desc {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repo-stars,
.commit-time {
  flex: none;
}

.commit-message {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
