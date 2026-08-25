#!/bin/bash
set -e

# StarX 0.6.0 配置迁移脚本
SERVER="add@103.40.14.25"
PORT="19198"
REMOTE_PLUGIN_DIR="/data/minecraft/vc/plugins/starx"
REMOTE_JAR="/data/minecraft/vc/plugins/starx-universal.jar"
BACKUP_DIR="/data/minecraft/vc/plugins/starx-backup-$(date +%Y%m%d-%H%M%S)"

LOCAL_TEMP="/tmp/starx-migration-$(date +%s)"
mkdir -p "$LOCAL_TEMP"

echo "=== StarX 0.6.0 配置迁移开始 ==="
echo "时间: $(date)"

# 1. 下载旧配置文件
echo ""
echo "[1/6] 从服务器下载配置文件..."
ssh -p $PORT -o StrictHostKeyChecking=no $SERVER "cd $REMOTE_PLUGIN_DIR && tar czf - ." > $LOCAL_TEMP/starx-backup.tar.gz 2>/dev/null

cd $LOCAL_TEMP
rm -rf starx-old
mkdir -p starx-old
cd starx-old
tar xzf ../starx-backup.tar.gz

echo "[2/6] 分析旧配置结构..."
OLD_VERSION=$(grep -E "^schema-version:" config.yml 2>/dev/null || echo "unknown")
echo "旧配置 schema-version: $OLD_VERSION"

# 2. 创建新配置目录
NEW_CONFIG_DIR="$LOCAL_TEMP/starx-new"
mkdir -p "$NEW_CONFIG_DIR/config"
mkdir -p "$NEW_CONFIG_DIR/uworld"

# 复制数据库文件（兼容的）
cp -f $LOCAL_TEMP/starx-old/data.db $NEW_CONFIG_DIR/ 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/*.json $NEW_CONFIG_DIR/ 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/server-icon.png $NEW_CONFIG_DIR/ 2>/dev/null || true

# 3. 生成新 config.yml
echo ""
echo "[3/6] 迁移 config.yml..."
cat > $NEW_CONFIG_DIR/config.yml << 'EOF'
schema-version: 6
config-files:
  directory: config
  files:
    - core.yml
    - auth.yml
    - network.yml
    - modules.yml
    - uworld.yml
EOF

# 4. 复制兼容的配置文件
echo "[4/6] 复制兼容的配置文件..."
cp -f $LOCAL_TEMP/starx-old/config/auth.yml $NEW_CONFIG_DIR/config/auth.yml 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/config/core.yml $NEW_CONFIG_DIR/config/core.yml 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/config/network.yml $NEW_CONFIG_DIR/config/network.yml 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/config/modules.yml $NEW_CONFIG_DIR/config/modules.yml 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/config/uworld.yml $NEW_CONFIG_DIR/config/uworld.yml 2>/dev/null || true
cp -f $LOCAL_TEMP/starx-old/uworld/core.yml $NEW_CONFIG_DIR/uworld/core.yml 2>/dev/null || true

# 5. 创建 update.yml（新版本新增）
echo "[5/6] 创建 update.yml..."
cat > $NEW_CONFIG_DIR/config/update.yml << 'EOF'
check-update: true
check-interval-days: 1
EOF

echo ""
echo "[6/6] 打包并上传到服务器..."
cd $LOCAL_TEMP
tar czf starx-new-config.tar.gz starx-new
scp -P $PORT -o StrictHostKeyChecking=no starx-universal-0.6.0.jar $SERVER:/tmp/ 2>/dev/null
scp -P $PORT -o StrictHostKeyChecking=no starx-new-config.tar.gz $SERVER:/tmp/ 2>/dev/null

echo ""
echo "=== 执行服务器端部署 ==="
ssh -p $PORT -o StrictHostKeyChecking=no $SERVER << 'SERVER_SCRIPT'
set -e
REMOTE_PLUGIN_DIR="/data/minecraft/vc/plugins/starx"
REMOTE_JAR="/data/minecraft/vc/plugins/starx-universal.jar"
BACKUP_DIR="/data/minecraft/vc/plugins/starx-backup-$(date +%Y%m%d-%H%M%S)"

echo "服务器端：备份旧插件..."
if [ -d "$REMOTE_PLUGIN_DIR" ]; then
    mv "$REMOTE_PLUGIN_DIR" "${BACKUP_DIR}"
    echo "旧插件已备份到: ${BACKUP_DIR}"
fi

mkdir -p "$REMOTE_PLUGIN_DIR/config"
mkdir -p "$REMOTE_PLUGIN_DIR/uworld"

echo "服务器端：解压新配置..."
cd /tmp
rm -rf starx-new
mkdir -p starx-new
tar xzf starx-new-config.tar.gz -C starx-new

cp -rf starx-new/starx-new/* "$REMOTE_PLUGIN_DIR/"
cp -f /tmp/starx-universal-0.6.0.jar "$REMOTE_JAR"

chmod 644 "$REMOTE_JAR"
chmod -R 755 "$REMOTE_PLUGIN_DIR"
chown -R add:add "$REMOTE_PLUGIN_DIR"
chown add:add "$REMOTE_JAR"

echo ""
echo "验证结果:"
ls -la "$REMOTE_JAR"
echo "--- 插件目录 ---"
ls -la "$REMOTE_PLUGIN_DIR/"
echo "--- 配置目录 ---"
ls -la "$REMOTE_PLUGIN_DIR/config/"

echo ""
echo "=== 部署完成 ==="
echo "新插件: $REMOTE_JAR"
echo "新配置: $REMOTE_PLUGIN_DIR/"
echo "旧配置备份: ${BACKUP_DIR}"
SERVER_SCRIPT

echo ""
echo "=== 本地清理 ==="
rm -rf $LOCAL_TEMP
rm -f starx-universal-0.6.0.jar

echo ""
echo "✅ StarX 0.6.0 部署和配置迁移完成！"
echo "📝 需要重启 Velocity 服务器才能生效"
echo "💾 旧配置已备份到: ${BACKUP_DIR}"
