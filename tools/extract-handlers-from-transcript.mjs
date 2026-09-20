import fs from 'node:fs'

const transcript = process.argv[2]
const lines = fs.readFileSync(transcript, 'utf8').split(/\n/).filter(Boolean)
const outDir = 'tmp/transcript-full'
fs.mkdirSync(outDir, { recursive: true })

/** @type {Map<string, string>} */
const best = new Map()

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
    const input = block.input || {}
    const p = String(input.path || '').replace(/\\/g, '/')
    const text = input.contents || input.new_string || ''
    if (!text) continue

    const keys = []
    if (p.includes('handlers.js')) keys.push('handlers.js')
    if (p.includes('mcVerify.js') && text.includes('remainingInitQuota')) keys.push('mcVerify.js')
    if (p.includes('skin/routes.js') && text.includes('BATCH_REVIEW')) keys.push('skin-routes-batch')
    if (p.includes('security/routes.js') && text.includes('analytics')) keys.push('security-routes')
    if (p.includes('retryRoute.js') && !text.includes('501')) keys.push('retryRoute.js')
    if (p.includes('api-contract-routes.test.js')) keys.push('api-contract-routes.test.js')
    if (p.includes('skin-review-flow.test.js')) keys.push('skin-review-flow.test.js')
    if (p.includes('publicProfile.js')) keys.push('publicProfile.js')
    if (text.includes('handleLoginSuccess') && text.includes('verify_premium')) keys.push('handlers-snippet')

    for (const key of keys) {
      const prev = best.get(key) || ''
      if (text.length > prev.length) best.set(key, text)
    }
  }
}

for (const [key, text] of best) {
  const target = `${outDir}/${key}`
  fs.writeFileSync(target, text, 'utf8')
  console.log(key, text.length, '->', target)
}
