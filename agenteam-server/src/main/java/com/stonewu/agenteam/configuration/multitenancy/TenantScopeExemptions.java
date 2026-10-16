package com.stonewu.agenteam.configuration.multitenancy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多租户隔离豁免登记表（单一事实源）。
 *
 * <p>键的格式必须与 {@code TenantScopeExemptionTest} 的动态扫描结果一致：</p>
 * <ul>
 *   <li>XML 语句：{@code <mapper namespace>#<statementId>}</li>
 *   <li>Java 方法：{@code <SimpleClassName>#<methodName>}</li>
 * </ul>
 *
 * <p>该表是机器校验的基准：测试动态算出当前代码里「不带租户条件」的语句/方法集合，
 * 并断言其<b>精确等于</b>这里登记的集合。若有人新增一条不带租户条件的查询却未登记，
 * 测试会失败并提示差异。若某条登记已不再存在（被删除/已加租户条件），同样会失败。</p>
 *
 * <p>登记即代表人工复核结论：这些语句/方法可以没有租户条件。理由见
 * {@link TenantScopeExemption}。</p>
 */
public final class TenantScopeExemptions {

    private TenantScopeExemptions() {
    }

    private static void put(Map<String, TenantScopeExemption> map, String key, TenantScopeExemption reason) {
        if (map.put(key, reason) != null) {
            throw new IllegalStateException("重复的豁免登记键: " + key);
        }
    }

    /** MyBatis XML 映射语句中，不带 enterprise_id / scope 条件但经复核可豁免的集合。 */
    public static Map<String, TenantScopeExemption> xmlExemptions() {
        Map<String, TenantScopeExemption> m = new LinkedHashMap<>();
        String NS = "com.stonewu.agenteam.mapper.";

        put(m, NS + "agent.AgentHireApplicationSqlMapper#expireAgentHireRequest", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "announcement.AnnouncementReadMapper#saveRead", TenantScopeExemption.PLATFORM_SCOPED_TABLE);
        put(m, NS + "auth.AuthSqlMapper#superAdminInitialized", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "auth.AuthSqlMapper#lockUser", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "auth.AuthSqlMapper#initializedByAnother", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "auth.AuthSqlMapper#replacePassword", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "auth.AuthSqlMapper#invalidateUnusedTokens", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "auth.AuthTokenSqlMapper#findLockedAuthToken", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "auth.AuthTokenSqlMapper#mailRetryAfterAuthToken", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "auth.AuthTokenSqlMapper#cancelUnusedEmailVerificationsAuthToken", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "auth.IdentityRetentionSqlMapper#expiredTokenIds", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "auth.IdentityRetentionSqlMapper#expiredRequestIds", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "background.BackgroundJobSqlMapper#lockOwnedBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, NS + "edition.InstallationPreflightMapper#tableNames", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "enterprise.EnterpriseSqlMapper#lockEnterpriseEnterprise", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "execution.ExecutionEventSqlMapper#appendAgentEvent", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, NS + "execution.RunJobSqlMapper#claimBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, NS + "execution.RunJobSqlMapper#expiredAndLockedBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, NS + "execution.RunJobSqlMapper#lastEventBatchBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, NS + "execution.RunJobSqlMapper#savedEventBatchBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, NS + "http.ApiRequestMapper#findLocked", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, NS + "memory.MemorySqlMapper#expireAgentMemory2", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "operations.OperationalStateSqlMapper#readBackgroundJob", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "operations.OperationalStateSqlMapper#readAgentRun", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "operations.OperationalStateSqlMapper#readAgentEvent", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, NS + "project.WorkspaceProjectSqlMapper#conflictingDirectory", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, NS + "tool.ToolCallSqlMapper#failAbandonedManualReadsToolCall", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, NS + "usage.QuotaSqlMapper#lockEnterpriseEnterprise", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);

        return Collections.unmodifiableMap(m);
    }

    /** Java 侧（MyBatis-Plus Wrapper / Mapper 调用）不带租户条件但经复核可豁免的集合。 */
    public static Map<String, TenantScopeExemption> javaExemptions() {
        Map<String, TenantScopeExemption> m = new LinkedHashMap<>();

        // 平台作用域表：scope 列在 service 层统一处理（P2-B 已硬化）
        put(m, "AnnouncementManagementService#delete", TenantScopeExemption.PLATFORM_SCOPED_TABLE);
        put(m, "AnnouncementManagementService#enterpriseOptions", TenantScopeExemption.PLATFORM_SCOPED_TABLE);
        put(m, "AnnouncementManagementService#status", TenantScopeExemption.PLATFORM_SCOPED_TABLE);
        put(m, "AnnouncementManagementService#update", TenantScopeExemption.PLATFORM_SCOPED_TABLE);
        put(m, "AnnouncementUserService#readAll", TenantScopeExemption.PLATFORM_SCOPED_TABLE);
        put(m, "AnnouncementViewMapper#views", TenantScopeExemption.ID_LIST_FROM_SCOPED_INPUT);

        // 全局身份/令牌/权限/运营态/企业根/用户偏好/安装引导：本身无 enterprise_id 维度
        put(m, "AuthMapper#findByEmail", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "AuthMapper#findByLoginIdentifier", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "AuthMapper#findByUsername", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "AuthMapper#setInitialEmail", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "AuthSqlMapper#updateProfile", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "AuthSqlMapper#updateVerifiedEmail", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "BuiltinPluginResourceMapper#enterprisesAfter", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "ExecutionEventSqlMapper#legacyDeltas", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "IdentityRetentionMapper#mailJobs", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "IdentityRetentionMapper#requests", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "IdentityRetentionMapper#tokens", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "InstallationService#lockBeforeBootstrap", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "OperationalStateMapper#read", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);
        put(m, "SysPermissionTableMapper#listPermissionsSysPermission", TenantScopeExemption.GLOBAL_TABLE_NO_TENANT_COLUMN);

        // 主键 / 全局唯一键（id、hash、publicLoginKey、storageKey、stateHash、userId、enterpriseId 主键）
        put(m, "AuthTokenSqlMapper#consumeAuthToken", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "AuthTokenSqlMapper#findByHashAuthToken", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "AuthTokenSqlMapper#findByIdAuthToken", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "ChannelAuthorizationService#publicConnection", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "ChannelOauthStore#claim", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "ChannelOauthStore#fail", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "EnterpriseTableMapper#enterpriseEnterprise", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "EnterpriseTableMapper#periodEnterprise", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "FileSqlMapper#referencesKeyFileObject", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "OrganizationViewSqlMapper#enterpriseEnterprise", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "UserPreferenceMapper#completionEnabledUserPreference", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);
        put(m, "UserPreferenceMapper#updatePreference", TenantScopeExemption.PRIMARY_KEY_OR_UNIQUE);

        // 后台调度/作业：无 HTTP 租户上下文，按 id/状态加锁后逐条处理
        put(m, "BackgroundJobSqlMapper#assignLease", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "BackgroundJobSqlMapper#finishBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "BackgroundJobSqlMapper#renewBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "BackgroundJobSqlMapper#setAttemptLimitBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "MailQueueSqlMapper#replacePayloadBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "RunJobSqlMapper#assignLease", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "RunJobSqlMapper#parkBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "RunJobSqlMapper#renewBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "RunJobSqlMapper#requeueBackgroundJob", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);
        put(m, "ScheduleActionStore#candidates", TenantScopeExemption.BACKGROUND_JOB_NO_TENANT_CONTEXT);

        // 全局保留清理：按状态/时间批量处理内部数据，不向用户返回跨租户内容
        put(m, "ChannelOauthStore#expire", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, "IntegrationRetentionService#clearPayloads", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);
        put(m, "IntegrationRetentionService#expireAuthorizations", TenantScopeExemption.GLOBAL_MAINTENANCE_SWEEP);

        return Collections.unmodifiableMap(m);
    }
}
