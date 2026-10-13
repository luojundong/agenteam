package com.stonewu.agenteam.controller.audit;

import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.support.SharedEnterpriseTestEdition;
import com.stonewu.agenteam.support.execution.ExecutionApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证操作审计查询端点的授权、过滤、分页与返回结构。企业管理员（自带 audit.view）可读取；
 * 缺权限返回 403。需要 MariaDB 容器（Testcontainers），在 CI 中执行。
 */
@Import(SharedEnterpriseTestEdition.class)
class AuditEventApiTest extends ExecutionApiTestSupport {

    @Autowired
    private AuditEventMapper audit;

    @Test
    void enterpriseAdminCanListAuditEvents() throws Exception {
        var data = data(get(base() + "/audit-events").andExpect(status().isOk()).andReturn()).path("items");
        assertTrue(data.isArray(), "审计列表应返回 items 数组");
    }

    @Test
    void auditEventsRejectMissingViewPermission() throws Exception {
        String role = permissions.listUserRoleIds(admin, enterprise).getFirst();
        var restored = Set.copyOf(permissions.listPermissionCodes(admin, enterprise));
        try {
            permissions.replaceRolePermissions(enterprise, role, Set.of());
            get(base() + "/audit-events").andExpect(status().isForbidden());
        } finally {
            permissions.replaceRolePermissions(enterprise, role, restored);
        }
    }

    @Test
    void auditEventsFilterByActionAndPaginateByCreatedAtDesc() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            audit.append(enterprise, admin, "审计测试员", "audit.test", "audit_event", "obj-" + i,
                "摘要-" + i, "{}", "success", UUID.randomUUID().toString(), now.minusSeconds(i));
        }
        var page1 = data(get(base() + "/audit-events?limit=2&action=audit.test").andExpect(status().isOk()).andReturn());
        assertEquals(2, page1.path("items").size());
        assertTrue(page1.path("hasMore").asBoolean());
        assertEquals("obj-0", page1.path("items").get(0).path("objectId").asText());
        assertEquals("obj-1", page1.path("items").get(1).path("objectId").asText());

        String cursor = page1.path("nextCursor").asText();
        assertNotNull(cursor);
        var page2 = data(get(base() + "/audit-events?limit=2&action=audit.test&cursor=" + cursor)
            .andExpect(status().isOk()).andReturn());
        assertEquals(2, page2.path("items").size());
        assertEquals("obj-2", page2.path("items").get(0).path("objectId").asText());
        assertEquals("obj-3", page2.path("items").get(1).path("objectId").asText());

        var page3 = data(get(base() + "/audit-events?limit=2&action=audit.test&cursor="
            + page2.path("nextCursor").asText()).andExpect(status().isOk()).andReturn());
        assertEquals(1, page3.path("items").size());
        assertEquals("obj-4", page3.path("items").get(0).path("objectId").asText());
        assertFalse(page3.path("hasMore").asBoolean(), "最后一页不应还有更多");
    }

    @Test
    void auditEventsEndpointRejectsUnknownVerb() throws Exception {
        change(HttpMethod.DELETE, base() + "/audit-events", null, null, UUID.randomUUID().toString())
            .andExpect(status().isMethodNotAllowed());
    }

    private ResultActions get(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
            .cookie(cookie).header("X-CSRF-Token", csrf).header("Origin", "http://localhost:3000"));
    }
}
