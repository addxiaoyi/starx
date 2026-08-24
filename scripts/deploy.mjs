#!/usr/bin/env node
// StarMC 一键部署脚本
// 用法: node scripts/deploy.mjs
//       npm run deploy:build

import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createGzip } from 'node:zlib';
import { pipeline } from 'node:stream/promises';
import { createReadStream, createWriteStream } from 'node:fs';

// ─── ANSI 彩色输出 ───────────────────────────────────────────────────────────
const C = {
  reset:   '\x1b[0m',
  bold:    '\x1b[1m',
  dim:     '\x1b[2m',
  red:     '\x1b[31m',
  green:   '\x1b[32m',
  yellow:  '\x1b[33m',
  blue:    '\x1b[34m',
  magenta: '\x1b[35m',
  cyan:    '\x1b[36m',
  white:   '\x1b[37m',
  bgGreen: '\x1b[42m',
  bgRed:   '\x1b[41m',
};

function info(msg)    { console.log(`${C.cyan}[INFO]${C.reset}  ${msg}`); }
function success(msg) { console.log(`${C.green}[  OK]${C.reset}  ${msg}`); }
function warn(msg)    { console.log(`${C.yellow}[WARN]${C.reset}  ${msg}`); }
function fail(msg)    { console.error(`${C.red}[FAIL]${C.reset}  ${msg}`); }

function step(n, total, title) {
  console.log('');
  console.log(`${C.bold}${C.blue}━━━ 步骤 ${n}/${total}: ${title} ━━━${C.reset}`);
}

function banner(text) {
  const line = '═'.repeat(text.length + 4);
  console.log('');
  console.log(`${C.bold}${C.magenta}╔${line}╗${C.reset}`);
  console.log(`${C.bold}${C.magenta}║  ${text}  ║${C.reset}`);
  console.log(`${C.bold}${C.magenta}╚${line}╝${C.reset}`);
  console.log('');
}

function run(cmd, opts = {}) {
  info(`> ${C.dim}${cmd}${C.reset}`);
  try {
    execSync(cmd, {
      stdio: 'inherit',
      encoding: 'utf-8',
      ...opts,
    });
  } catch (err) {
    fail(`命令执行失败: ${cmd}`);
    if (err.status) fail(`退出码: ${err.status}`);
    process.exit(1);
  }
}

function runQuiet(cmd, opts = {}) {
  try {
    return execSync(cmd, { encoding: 'utf-8', ...opts }).trim();
  } catch {
    return null;
  }
}

// ─── 路径常量 ─────────────────────────────────────────────────────────────────
const __filename = fileURLToPath(import.meta.url);
const __dirname  = path.dirname(__filename);
const ROOT       = path.resolve(__dirname, '..');
const FRONTEND   = path.join(ROOT, '重构', 'starmc');
const BACKEND    = path.join(ROOT, '重构', 'backend');
const DIST_DIR   = path.join(ROOT, 'dist');

const TOTAL_STEPS = 6;

// ─── 1. 检查环境 ─────────────────────────────────────────────────────────────
function checkEnv() {
  step(1, TOTAL_STEPS, '环境检查');

  // Node.js 版本
  const nodeVersion = process.versions.node;
  const [major] = nodeVersion.split('.').map(Number);
  if (major < 20) {
    fail(`Node.js 版本过低: v${nodeVersion}（需要 >= 20.19.0）`);
    process.exit(1);
  }
  success(`Node.js v${nodeVersion}`);

  // npm
  const npmVersion = runQuiet('npm --version');
  if (!npmVersion) {
    fail('未检测到 npm');
    process.exit(1);
  }
  success(`npm v${npmVersion}`);

  // 前端依赖
  const feNodeModules = path.join(FRONTEND, 'node_modules');
  if (!fs.existsSync(feNodeModules)) {
    warn('前端 node_modules 不存在，正在安装依赖...');
    run('npm install', { cwd: FRONTEND });
  }
  success('前端依赖已就绪');

  // 后端依赖
  const beNodeModules = path.join(BACKEND, 'node_modules');
  if (!fs.existsSync(beNodeModules)) {
    warn('后端 node_modules 不存在，正在安装依赖...');
    run('npm install', { cwd: BACKEND });
  }
  success('后端依赖已就绪');
}

// ─── 2. 前端构建 ─────────────────────────────────────────────────────────────
function buildFrontend() {
  step(2, TOTAL_STEPS, '前端构建');

  const distPath = path.join(FRONTEND, 'dist');
  // 清理旧产物
  if (fs.existsSync(distPath)) {
    info('清理旧的前端构建产物...');
    fs.rmSync(distPath, { recursive: true, force: true });
  }

  if (process.platform === 'win32') {
    run('powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-frontend-ascii.ps1 -SkipTests -SkipTypecheck', { cwd: ROOT });
  } else {
    run('npm run build', { cwd: FRONTEND });
  }

  // 验证产物
  if (!fs.existsSync(distPath)) {
    fail('前端构建产物目录不存在: 重构/starmc/dist/');
    process.exit(1);
  }

  const indexHtml = path.join(distPath, 'index.html');
  if (!fs.existsSync(indexHtml)) {
    fail('前端构建产物缺少 index.html');
    process.exit(1);
  }

  const fileCount = countFiles(distPath);
  success(`前端构建完成 (${fileCount} 个文件)`);
}

// ─── 3. 组装部署目录 ─────────────────────────────────────────────────────────
function assembleDist() {
  step(3, TOTAL_STEPS, '组装部署目录');

  // 清理旧的 dist
  if (fs.existsSync(DIST_DIR)) {
    fs.rmSync(DIST_DIR, { recursive: true, force: true });
  }
  fs.mkdirSync(DIST_DIR, { recursive: true });

  // 复制前端产物到 dist/frontend/
  const frontendDist = path.join(FRONTEND, 'dist');
  const targetFrontend = path.join(DIST_DIR, 'frontend');
  info('复制前端产物 → dist/frontend/');
  copyDirRecursive(frontendDist, targetFrontend);

  // 复制后端代码到 dist/backend/（排除 node_modules, .env*）
  const targetBackend = path.join(DIST_DIR, 'backend');
  info('复制后端代码 → dist/backend/（不含 node_modules）');
  copyDirRecursive(BACKEND, targetBackend, [
    'node_modules',
    'data',
    'tests',
    'coverage',
    '.git',
    '.env',
    '.env.local',
    '.env.generated',
  ]);

  const feCount = countFiles(targetFrontend);
  const beCount = countFiles(targetBackend);
  success(`部署目录组装完成: 前端 ${feCount} 文件, 后端 ${beCount} 文件`);
}

// ─── 4. 后端测试 ─────────────────────────────────────────────────────────────
function runBackendTests() {
  step(4, TOTAL_STEPS, '后端测试');

  // 检查 tests 目录是否存在
  const testsDir = path.join(BACKEND, 'tests');
  if (!fs.existsSync(testsDir)) {
    warn('后端 tests/ 目录不存在，跳过测试');
    return;
  }

  // 列出测试文件
  const testFiles = findTestFiles(testsDir);
  if (testFiles.length === 0) {
    warn('未找到测试文件，跳过测试');
    return;
  }

  info(`发现 ${testFiles.length} 个测试文件`);
  run('npm test', { cwd: BACKEND });
  success('后端测试全部通过');
}

// ─── 5. 打包 ─────────────────────────────────────────────────────────────────
async function createArchive() {
  step(5, TOTAL_STEPS, '生成部署包');

  const timestamp = new Date()
    .toISOString()
    .replace(/[:\-T]/g, '')
    .replace(/\..+/, '');
  const archiveName = `starmc-deploy-${timestamp}.tar.gz`;
  const archivePath = path.join(ROOT, archiveName);

  // 使用 tar 命令打包（优先系统 tar，兼容 Windows 和 Linux）
  const tarCmd = process.platform === 'win32'
    ? `tar -czf "${archivePath}" -C "${DIST_DIR}" .`
    : `tar -czf "${archivePath}" -C "${DIST_DIR}" .`;

  const tarAvailable = runQuiet('tar --version');
  if (tarAvailable) {
    run(tarCmd);
  } else {
    // 回退：使用 Node.js 流式打包（简单的 .tar.gz 逻辑）
    fail('系统未安装 tar 命令，无法创建部署包');
    info('请手动打包 dist/ 目录');
    process.exit(1);
  }

  // 检查文件大小
  const stats = fs.statSync(archivePath);
  const sizeMB = (stats.size / 1024 / 1024).toFixed(2);
  success(`部署包生成: ${archiveName} (${sizeMB} MB)`);

  return { archiveName, archivePath, sizeMB };
}

// ─── 6. 输出部署指引 ─────────────────────────────────────────────────────────
function printGuide(archiveInfo) {
  step(6, TOTAL_STEPS, '部署指引');

  console.log(`
${C.bold}${C.green}============================================${C.reset}
${C.bold}${C.green}    StarMC 部署包已准备就绪${C.reset}
${C.bold}${C.green}============================================${C.reset}

${C.bold}部署包:${C.reset} ${archiveInfo.archiveName} (${archiveInfo.sizeMB} MB)

${C.bold}${C.cyan}── 部署步骤 ──${C.reset}

${C.bold}1.${C.reset} 上传部署包到服务器:
   ${C.dim}scp ${archiveInfo.archiveName} user@star-web.top:/tmp/${C.reset}

${C.bold}2.${C.reset} 在服务器上解压:
   ${C.dim}mkdir -p /var/www/starmc && cd /var/www/starmc${C.reset}
   ${C.dim}tar -xzf /tmp/${archiveInfo.archiveName}${C.reset}

${C.bold}3.${C.reset} 部署前端（Nginx 静态服务）:
   ${C.dim}# 将 frontend/ 内容放到 Nginx root 指向的目录${C.reset}
   ${C.dim}cp -r frontend/* /var/www/starmc/dist/${C.reset}

${C.bold}4.${C.reset} 部署后端:
   ${C.dim}cd backend/${C.reset}
   ${C.dim}npm install --omit=dev${C.reset}
   ${C.dim}# 配置 .env.generated（参照 tools/starmc-simple-config.env）${C.reset}
   ${C.dim}npm run start${C.reset}
   ${C.dim}# 或使用 pm2: pm2 start src/envLayering.js -- --run src/server.js${C.reset}

${C.bold}5.${C.reset} 配置 Nginx（参照 docs/DEPLOY.md 中的示例配置）

${C.bold}6.${C.reset} 验证:
   ${C.dim}curl -s https://star-web.top/api/health${C.reset}
   ${C.dim}curl -s https://star-web.top/api/public/bootstrap${C.reset}

${C.bold}${C.cyan}── 注意事项 ──${C.reset}

  - 后端需要 Node.js >= 20.19.0
  - 确保 .env.generated 或环境变量已正确配置
  - 后端默认监听端口 8787
  - 使用 pm2 或 systemd 管理后端进程
  - 参照 docs/DEPLOY.md 获取完整部署文档
`);
}

// ─── 工具函数 ─────────────────────────────────────────────────────────────────

/** 递归复制目录（排除指定名称） */
function copyDirRecursive(src, dest, excludeNames = []) {
  fs.mkdirSync(dest, { recursive: true });
  const entries = fs.readdirSync(src, { withFileTypes: true });

  for (const entry of entries) {
    if (excludeNames.includes(entry.name)) continue;
    if (entry.name.startsWith('.env')) continue;
    if (entry.name.endsWith('.log')) continue;

    const srcPath  = path.join(src, entry.name);
    const destPath = path.join(dest, entry.name);

    if (entry.isDirectory()) {
      copyDirRecursive(srcPath, destPath, excludeNames);
    } else {
      fs.copyFileSync(srcPath, destPath);
    }
  }
}

/** 递归统计文件数量 */
function countFiles(dir) {
  let count = 0;
  if (!fs.existsSync(dir)) return 0;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      count += countFiles(p);
    } else {
      count++;
    }
  }
  return count;
}

/** 递归查找测试文件 */
function findTestFiles(dir) {
  const results = [];
  if (!fs.existsSync(dir)) return results;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...findTestFiles(p));
    } else if (/\.(test|spec)\.m?js$/.test(entry.name)) {
      results.push(p);
    }
  }
  return results;
}

// ─── 主流程 ───────────────────────────────────────────────────────────────────
async function main() {
  const startTime = Date.now();

  banner('StarMC 生产部署脚本');

  info(`项目根目录: ${ROOT}`);
  info(`前端路径:   ${FRONTEND}`);
  info(`后端路径:   ${BACKEND}`);
  info(`部署输出:   ${DIST_DIR}`);

  // 验证关键路径存在
  if (!fs.existsSync(FRONTEND)) {
    fail(`前端目录不存在: ${FRONTEND}`);
    process.exit(1);
  }
  if (!fs.existsSync(BACKEND)) {
    fail(`后端目录不存在: ${BACKEND}`);
    process.exit(1);
  }

  // 依次执行各步骤
  checkEnv();
  buildFrontend();
  assembleDist();
  runBackendTests();
  const archiveInfo = await createArchive();
  printGuide(archiveInfo);

  // 总结
  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
  console.log(`${C.bold}${C.bgGreen}${C.white} 部署包构建完成 ${C.reset} 耗时 ${elapsed}s`);
  console.log('');
}

main().catch((err) => {
  fail(`部署脚本异常退出: ${err.message}`);
  console.error(err);
  process.exit(1);
});
