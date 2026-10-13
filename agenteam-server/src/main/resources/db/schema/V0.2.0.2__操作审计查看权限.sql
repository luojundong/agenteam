-- 操作审计查看权限（audit.view）已随基线 V0__init.sql 写入 sys_permission，
-- 并由内置角色模板在新企业开通时自动授予企业管理员。此迁移仅为「已存在」的企业
-- 补授该权限（内置角色目录不回填既有企业），保证存量部署也能查询审计记录。
-- 授予动作幂等：已存在的授予关系不会重复插入。
INSERT INTO sys_role_permission (enterprise_id, role_id, permission_code)
SELECT r.enterprise_id, r.id, 'audit.view'
FROM sys_role r
WHERE r.builtin = 1
  AND r.code = 'enterprise-admin'
  AND r.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM sys_role_permission p
    WHERE p.enterprise_id = r.enterprise_id
      AND p.role_id = r.id
      AND p.permission_code = 'audit.view'
  );

UPDATE enterprise SET permission_version = permission_version + 1;
