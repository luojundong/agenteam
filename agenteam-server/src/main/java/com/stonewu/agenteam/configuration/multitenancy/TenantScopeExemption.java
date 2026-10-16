package com.stonewu.agenteam.configuration.multitenancy;

/**
 * 多租户隔离豁免理由。
 *
 * <p>P2-F 的结论是：不引入 {@code TenantLineInnerInterceptor}（详见
 * docs/deployment/multitenancy-assessment.md）。取而代之，所有「不带租户条件」
 * 的数据访问语句/方法都必须在此显式登记，并说明为何可以没有租户条件。
 * {@code TenantScopeExemptionTest} 会在每次构建时动态算出真实的「无租户条件集合」，
 * 并断言其与该登记表完全一致——任何新增的无租户条件查询都会让测试失败，
 * 强制开发者显式登记并说明理由。</p>
 */
public enum TenantScopeExemption {

    /**
     * 按主键或全局唯一键过滤。记录本身全局唯一，无法命中其他租户的行。
     */
    PRIMARY_KEY_OR_UNIQUE(
            "按主键或全局唯一键（如 publicLoginKey / storageKey / stateHash）过滤，记录全局唯一，无法跨租户命中"),

    /**
     * 按上游已带租户/作用域上下文的 id 列表过滤。过滤值来自已经过作用域校验的输入。
     */
    ID_LIST_FROM_SCOPED_INPUT(
            "按上游已带租户/作用域上下文的 id 列表过滤，过滤值本身来自经过作用域校验的查询结果"),

    /**
     * 全局表，本身没有租户维度（身份/令牌/权限/企业根/用户偏好/运营态/安装）。
     */
    GLOBAL_TABLE_NO_TENANT_COLUMN(
            "全局表，数据模型本身不携带 enterprise_id 维度（身份账户、令牌、RBAC 权限、企业根、用户偏好、运营态、安装引导）"),

    /**
     * 后台维护/保留清理：按状态或时间批量处理内部数据，不向用户返回跨租户内容。
     */
    GLOBAL_MAINTENANCE_SWEEP(
            "后台维护/保留清理作业，按状态或时间批量处理内部数据，结果不向终端用户返回跨租户内容"),

    /**
     * 调度线程执行，没有 HTTP 租户 ThreadLocal 上下文，按 id/状态加锁后逐条处理。
     */
    BACKGROUND_JOB_NO_TENANT_CONTEXT(
            "调度线程执行，不存在 HTTP 请求也就没有租户 ThreadLocal 上下文，按 id/状态加锁后逐条处理各自记录"),

    /**
     * 插入新行，无需 WHERE 子句；租户归属写入行字段而非过滤条件。
     */
    INSERT_NEW_ROW(
            "插入新行，无需 WHERE 子句；租户归属作为行字段写入，不存在跨租户读取风险"),

    /**
     * 平台作用域表：作用域由 scope 列表达，隔离逻辑在 service 层处理（P2-B 已硬化）。
     */
    PLATFORM_SCOPED_TABLE(
            "平台作用域表，作用域由 scope 列表达，租户/平台隔离逻辑在 service 层统一处理（P2-B 已硬化）");

    private final String rationale;

    TenantScopeExemption(String rationale) {
        this.rationale = rationale;
    }

    public String rationale() {
        return rationale;
    }
}
