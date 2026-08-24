#!/usr/bin/env node
/**
 * Copy legacy website public/ assets into 重构/starmc/public/ (same URL paths).
 *
 * Usage:
 *   node tools/copy-legacy-public.mjs --from "D:/path/to/old/public"
 *   node tools/copy-legacy-public.mjs   # auto-detect known backup paths
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(__dirname, '..')
const TARGET = path.join(REPO_ROOT, '重构', 'starmc', 'public')

const PRESERVE_FILES = new Set(['ASSETS.placement.txt'])

const AUTO_SOURCES = [
  path.join(REPO_ROOT, '重构-前端备份-20260707-134037', 'public'),
  path.join(REPO_ROOT, '重构-备份-20260707-133828', 'public'),
  path.join(REPO_ROOT, '重构', 'public'),
]

function parseArgs() {
  const idx = process.argv.indexOf('--from')
  if (idx >= 0 && process.argv[idx + 1]) {
    return path.resolve(process.argv[idx + 1])
  }
  for (const candidate of AUTO_SOURCES) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory()) {
      return candidate
    }
  }
  return null
}

function copyRecursive(src, dest) {
  let copied = 0
  let skipped = 0
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    if (entry.name.startsWith('.')) continue
    const srcPath = path.join(src, entry.name)
    const destPath = path.join(dest, entry.name)
    if (entry.isDirectory()) {
      fs.mkdirSync(destPath, { recursive: true })
      const sub = copyRecursive(srcPath, destPath)
      copied += sub.copied
      skipped += sub.skipped
      continue
    }
    if (PRESERVE_FILES.has(entry.name) && fs.existsSync(destPath)) {
      skipped += 1
      continue
    }
    fs.mkdirSync(path.dirname(destPath), { recursive: true })
    fs.copyFileSync(srcPath, destPath)
    copied += 1
  }
  return { copied, skipped }
}

function main() {
  const source = parseArgs()
  if (!source) {
    console.error('[copy-legacy-public] 未找到源目录。请指定：')
    console.error('  node tools/copy-legacy-public.mjs --from "路径\\到\\旧站\\public"')
    console.error('已尝试：')
    for (const p of AUTO_SOURCES) {
      console.error(`  - ${p}`)
    }
    process.exit(1)
  }

  if (!fs.existsSync(source)) {
    console.error(`[copy-legacy-public] 源不存在: ${source}`)
    process.exit(1)
  }

  fs.mkdirSync(TARGET, { recursive: true })
  const { copied, skipped } = copyRecursive(source, TARGET)

  console.log('[copy-legacy-public] 完成')
  console.log(`  from:   ${source}`)
  console.log(`  to:     ${TARGET}`)
  console.log(`  copied: ${copied} files`)
  if (skipped) console.log(`  skipped: ${skipped} (保留目标已有说明文件)`)
  console.log('  验证: http://127.0.0.1:5173/favicon.svg')
}

main()
