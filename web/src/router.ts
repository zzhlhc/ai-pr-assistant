import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'submit', component: () => import('./views/SubmitView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('./views/TaskListView.vue') },
    { path: '/tasks/:id', name: 'task-detail', component: () => import('./views/TaskDetailView.vue') },
  ],
})
