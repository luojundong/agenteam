package com.stonewu.agenteam.service.multitenancy;

import com.stonewu.agenteam.mapper.announcement.AnnouncementSqlMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseSqlMapper;
import com.stonewu.agenteam.mapper.http.ApiRequestMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseRow;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P2-B：把越权挡在 SQL 之前，而不是先取到记录（顺带加上行锁）再在服务层判断。
 *
 * <p>关注两类原本可以从别的作用域触及的记录：
 * <ul>
 *   <li>公告：{@code lock} 此前只按编号查询，锁住之后才由服务层比对归属；</li>
 *   <li>幂等记录：{@code completeProcessing} 此前只看编号与状态。</li>
 * </ul>
 *
 * <p>这里全部用真实 MySQL 校验，因为改动生效的位置就是 SQL 条件本身，
 * 用打桩验证不到。
 */
@SpringBootTest(properties = {"execution.worker.enabled=false", "execution.events.worker-enabled=false"})
@Import(SharedEnterpriseTestEdition.class)
class TenantScopeIsolationTest {

    private static final InvitationHttpTestEnvironment ENVIRONMENT = new InvitationHttpTestEnvironment();

    @Autowired
    private AnnouncementSqlMapper announcements;

    @Autowired
    private EnterpriseSqlMapper enterprises;

    @Autowired
    private AuthMapper users;

    @Autowired
    private ApiRequestMapper requests;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENVIRONMENT.properties(registry);
    }

    @AfterAll
    static void closeEnvironment() throws Exception {
        ENVIRONMENT.close();
    }

    @Test
    void enterpriseAnnouncementIsLockedOnlyByItsOwnEnterprise() {
        String author = author();
        String owner = enterprise(author);
        String other = enterprise(author);
        var row = announcement(author, "enterprise", owner);

        assertNotNull(announcements.lockOwned(owner, row), "本企业的公告必须能锁定，否则管理端会全线报不存在");
        assertNull(announcements.lockOwned(other, row), "别的企业不得锁定到本企业公告");
        assertNull(announcements.lockOwned(null, row), "平台口径（enterprise_id 为空）不得命中企业公告");
        assertNull(announcements.lockVisible(other, row), "别的企业成员不得看到本企业公告");
        assertNotNull(announcements.lockVisible(owner, row), "本企业成员必须能看到本企业公告");
    }

    @Test
    void platformAnnouncementIsVisibleToEveryEnterpriseButOwnedByNone() {
        String author = author();
        var row = announcement(author, "platform", null);

        assertNotNull(announcements.lockOwned(null, row), "平台公告以空企业代表平台口径");
        assertNull(announcements.lockOwned(enterprise(author), row), "任何具体企业都不是平台公告的归属");
        assertNotNull(announcements.lockVisible(enterprise(author), row), "平台公告对所有企业可见");
    }

    @Test
    void deletedAnnouncementIsNeverLocked() {
        String author = author();
        String owner = enterprise(author);
        var row = announcement(author, "enterprise", owner);

        var stored = announcements.selectById(row);
        stored.setDeletedAt(Instant.now());
        assertEquals(1, announcements.updateById(stored));

        assertNull(announcements.lockOwned(owner, row), "已删除公告不应再被锁定");
        assertNull(announcements.lockVisible(owner, row), "已删除公告不应再对成员可见");
    }

    @Test
    void idempotentCompletionIsLimitedToItsOwningScope() {
        String author = author();
        String owner = enterprise(author);
        String other = enterprise(author);
        var now = Instant.now();

        String id = request(author, owner, now);
        assertIdempotentCompletion(id, author, owner, other, now);

        String globalId = request(author, null, now);
        assertEquals(1, requests.completeProcessing(globalId, null, author, 200, "{}", now),
            "全局动作的幂等记录以空企业保存，也必须能以空企业完成");
        assertEquals(0, requests.completeProcessing(globalId, owner, author, 200, "{}", now),
            "全局记录不得被当作某个企业的记录完成");
    }

    private void assertIdempotentCompletion(String id, String author, String owner, String other, Instant now) {
        assertEquals(0, requests.completeProcessing(id, other, author, 200, "{}", now), "别的企业不得完成本次幂等记录");
        assertEquals(0, requests.completeProcessing(id, null, author, 200, "{}", now), "空企业不得完成企业幂等记录");
        assertEquals(0, requests.completeProcessing(id, owner, UUID.randomUUID().toString(), 200, "{}", now),
            "别的操作者不得完成本次幂等记录");
        assertEquals(1, requests.completeProcessing(id, owner, author, 200, "{}", now), "本作用域必须能正常完成");
        assertEquals(0, requests.completeProcessing(id, owner, author, 200, "{}", now), "完成过一次后不再重复生效");
    }

    private String request(String author, String enterpriseId, Instant now) {
        String id = UUID.randomUUID().toString();
        requests.insert(id, enterpriseId, author, enterpriseId == null ? "global" : "enterprise:" + enterpriseId,
            "p2b-operation", UUID.randomUUID().toString(), "hash", now);
        return id;
    }

    private String announcement(String author, String scope, String enterpriseId) {
        var row = new AnnouncementRow();
        String id = UUID.randomUUID().toString();
        row.setId(id);
        row.setScope(scope);
        row.setEnterpriseId(enterpriseId);
        row.setTitle("P2-B 越权隔离");
        row.setContent("用于校验锁定范围");
        row.setContentFormat("plain_text");
        row.setLevelCode("normal");
        row.setLevelPriority(10);
        row.setEnabled(false);
        row.setContentVersion(1L);
        row.setCreatedBy(author);
        row.setUpdatedBy(author);
        row.setRevision(1L);
        var now = Instant.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        assertEquals(1, announcements.insert(row), "公告未能写入");
        return id;
    }

    private String enterprise(String owner) {
        var row = new EnterpriseRow();
        String id = UUID.randomUUID().toString();
        var now = Instant.now();
        row.setId(id);
        row.setName("p2b-" + id.substring(0, 8));
        row.setCreatedBy(owner);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setQuotaPeriodStart(now);
        row.setQuotaPeriodEnd(now.plusSeconds(86400));
        assertEquals(1, enterprises.insert(row), "企业未能写入");
        return id;
    }

    private String author() {
        String id = UUID.randomUUID().toString();
        String username = "p2b-" + id.substring(0, 8);
        var now = Instant.now();
        users.insertUser(id, username, username, "越权隔离测试", false, now);
        return id;
    }
}
