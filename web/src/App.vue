<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

// 详情页 /tasks/xxx 也要把「评审记录」这个菜单项点亮
const activeMenu = computed(() => (route.path.startsWith('/tasks') ? '/tasks' : '/'))
</script>

<template>
  <el-container>
    <el-header class="header">
      <span class="brand">AI 代码评审助手</span>
      <el-menu mode="horizontal" router :default-active="activeMenu" :ellipsis="false">
        <el-menu-item index="/">发起评审</el-menu-item>
        <el-menu-item index="/tasks">评审记录</el-menu-item>
      </el-menu>
    </el-header>
    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style>
body {
  margin: 0;
  background: #f5f7fa;
}
</style>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 32px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.brand {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.main {
  padding: 24px;
}
</style>
