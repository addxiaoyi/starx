import fs from 'node:fs'
import path from 'node:path'

const transcript = process.argv[2]
if (!transcript) {
  console.error('usage: node tools/extract-transcript-backend.mjs <transcript.jsonl>')
  process.exit(1)
}

const lines = fs.readFileSync(transcript, 'utf8').split(/\n/).filter(Boolean)
const outDir = path.resolve('tmp/transcript-backend-extract')
fs.mkdirSync(outDir, { recursive: true })

let count = 0
for (const line of lines) {
  let row
  try {
    row = JSON.parse(line)
  } catch {
    continue
  }
  const content = row?.message?.content
  if (!Array.isArray(content)) continue
  for (const block of content) {
    if (block.type !== 'tool_use') continue
    const input = block.input
    if (!input?.path || !input?.contents) continue
    const p = String(input.path).replace(/\\/g, '/')
    if (!p.includes('重构/backend')) continue
    const rel = p.split('重构/backend/')[1]
    if (!rel) continue
    const target = path.join(outDir, rel)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, input.contents, 'utf8')
    console.log('extracted', rel, input.contents.length)
    count++
  }
}
console.log('total', count, '->', outDir)
