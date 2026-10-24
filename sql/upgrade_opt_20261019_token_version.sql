-- T14: 在明确选择的数据库内执行。先备份并检查 users 结构；不要更改 USE 指向原业务库。
-- 只添加缺失列；已有列必须另行确认是 BIGINT NOT NULL DEFAULT 0，不能用重跑掩盖结构差异。
-- 回退旧代码可保留此增量列；数据恢复使用备份，不通过 DROP COLUMN 撤销会话撤销记录。
SET @token_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'token_version'
);
SET @token_version_ddl = IF(@token_version_exists = 0,
    'ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0 COMMENT ''账号会话撤销版本''',
    'SELECT ''token_version already exists; verify column definition'' AS migration_status');
PREPARE token_version_migration FROM @token_version_ddl;
EXECUTE token_version_migration;
DEALLOCATE PREPARE token_version_migration;
