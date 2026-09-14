import type { Severity, TaskStatus } from '../types/task'

export const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'MAJOR', 'MINOR', 'INFO']

export const SEVERITY_TAG: Record<Severity, 'danger' | 'warning' | 'primary' | 'info'> = {
  CRITICAL: 'danger',
  MAJOR: 'warning',
  MINOR: 'primary',
  INFO: 'info',
}

export const STATUS_TAG: Record<TaskStatus, 'info' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  RUNNING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
}

export const STATUS_TEXT: Record<TaskStatus, string> = {
  PENDING: '排队中',
  RUNNING: '评审中',
  SUCCESS: '已完成',
  FAILED: '失败',
}

export function formatCost(cost: number | null | undefined): string {
  return cost == null ? '-' : `¥${cost.toFixed(4)}`
}

export function formatSeconds(ms: number | null | undefined): string {
  return ms == null ? '-' : `${(ms / 1000).toFixed(1)}s`
}

export function formatNumber(value: number | null | undefined): string {
  return value == null ? '-' : value.toLocaleString('zh-CN')
}

export function formatTime(value: string | null | undefined): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

export function shortSha(sha: string): string {
  return sha.slice(0, 8)
}

export function fileName(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1)
}

export function sortBySeverity<T extends { severity: Severity }>(issues: T[]): T[] {
  return [...issues].sort(
    (a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity),
  )
}
