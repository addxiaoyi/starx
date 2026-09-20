# StarMC 生产部署（star-web.top）

官网域名：**https://star-web.top**  
游戏进服域名（仅客户端连接，不由本 Nginx 托管）：`star-mc.top`、`max.star-mc.top`、`mc.star-mc.top`

## 架构

```
浏览器 → Nginx (star-web.top:443)
           ├── /          → 静态 SPA（Vite build dist）
           ├── /api/*     → Node API :8787
           └── /auth/*    → SuperTokens（同 API 进程）
```

## 1. 生成生产配置

```bash
# 编辑 tools/starmc-simple-config.env
STARMC_PROFILE=production
STARMC_PUBLIC_SITE_URL=https://star-web.top
STARMC_PUBLIC_API_URL=https://star-web.top
STARMC_TRUST_PROXY=true
# 填写全部 STARMC_*_SECRET 与 STARMC_SMTP_*

npm run config:doctor:simple
npm run config:frontend-env -- --write
```

将 `docs/frontend-env/.env.starmc.production.generated` 合并进 `重构/starmc/.env.production`（或 CI 注入）。

## 2. 构建前端

```bash
npm run frontend:copy-legacy-public -- --from "D:\path\to\legacy\public"   # 可选：迁入旧站图片/视频
npm run frontend:build
```

产物目录：`重构/starmc/dist/`

## 3. 启动后端

```bash
npm run backend:start
# 或 pm2 / systemd 托管 重构/backend（读取 .env.generated）
```

确保本机 `8787` 可被 Nginx 反代访问。

## 4. Nginx 示例

```nginx
server {
    listen 443 ssl http2;
    server_name star-web.top;

    ssl_certificate     /etc/letsencrypt/live/star-web.top/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/star-web.top/privkey.pem;

    root /var/www/starmc/dist;
    index index.html;

    # 静态资源长缓存
    location ~* \.(js|css|png|jpg|jpeg|gif|webp|svg|ico|woff2?)$ {
        try_files $uri =404;
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # API + SuperTokens 会话
    location /api/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;
    }

    location /auth/ {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # SSE 皮肤库（禁用缓冲）
    location /api/skins/library/stream {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding off;
    }

    # 只让皮肤纹理 PNG 命中静态目录；/skins/:id 必须继续进入 SPA。
    include /www/server/panel/vhost/nginx/extension/star-web.top/skins.conf;

    # SPA history fallback
    location / {
        try_files $uri $uri/ /index.html;
    }
}

server {
    listen 80;
    server_name star-web.top;
    return 301 https://$host$request_uri;
}
```

## 5. 上线检查清单

| 步骤 | 命令 / 验证 |
|------|-------------|
| 配置无 error | `npm run config:doctor` |
| SuperTokens 可达 | `npm run backend:doctor` |
| 健康检查 | `curl -s https://star-web.top/api/health` |
| 引导接口 | `curl -s https://star-web.top/api/public/bootstrap` |
| 插件桥接 | API 启动后 `npm run plugin:probe` |
| 前端页面 | 浏览器打开 https://star-web.top |
| 登录 | 邮箱验证码（需 SMTP）；开发兜底勿用于生产 |

## 6. 常见问题

| 现象 | 处理 |
|------|------|
| 页面 404 但 API 正常 | `try_files` 未指向 `dist/index.html` |
| `/skins/:id` 刷新后 404 | 使用 `docs/nginx/star-web-skins.conf`，不要用宽泛的 `location /skins/` |
| 登录后 401 | Cookie `SameSite`/域名与 `AUTH_API_DOMAIN` 不一致；检查 `TRUST_PROXY` |
| API 502 | 后端未启动或防火墙未放行 8787 |
| 皮肤 SSE 断开 | Nginx 需关闭 `proxy_buffering`（见上） |
| 静态图裂图 | 运行 `frontend:copy-legacy-public` 或检查 `public/` |

部署后用浏览器网络面板连续观察至少 35 秒：`/api/skins/library/stream` 应保持一个健康连接，`/api/skins/catalog` 与 `/api/skins/collections` 不应每 15 秒重复请求。主动断开 SSE 后才应看到轮询降级；审核通过时在线客户端应收到 `catalog_sync`。

## 7. SuperTokens 会话策略

浏览器会话由 SuperTokens Core 管理，Node API 不能安全地覆盖刷新令牌有效期。生产环境必须使用本站可控的 Core；不要将 `SUPERTOKENS_CONNECTION_URI` 指向共享试用 Core。

在自托管 Core 的配置中显式设置：

```yaml
refresh_token_validity: 2592000
```

变更前复制当前 Core 配置到带时间戳的备份目录。重启 Core 后，用一个新登录验证：`POST /auth/signinup/code/consume` 返回 200、`GET /api/auth/status` 为已认证、`GET /api/user/me` 返回 200，并在刷新 `/profile` 后保持登录。撤销第二个设备后，该设备下一次受保护请求必须变为未认证。

若 Core 健康检查或会话恢复失败，先还原 Core 配置并确认健康检查，再还原静态前端目录。

## 8. 与游戏服的关系

- **star-web.top**：网站、皮肤站、账号 API  
- **star-mc.top 等**：Minecraft 客户端进服地址，配置在前端 `重构/starmc/src/lib/serverAddresses.ts`  
- Velocity 插件回调 URL 使用 `PUBLIC_API_BASE_URL`（生产应为 `https://star-web.top`），见 `docs/SIMPLE_CONFIG.md` 与 `重构/backend/docs/PLUGIN_INTEGRATION.md`
