package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.resource.TagMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.model.resource.response.TagView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TagDeletionAuditTest {
    private final TagMapper tags = mock(TagMapper.class);
    private final EnterpriseAuthorizationService authorization = mock(EnterpriseAuthorizationService.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final ListPagination pagination = mock(ListPagination.class);
    private final Instant now = Instant.parse("2026-09-22T00:00:00Z");
    private final UserEntity user = new UserEntity("admin", "admin", "", "管理员", "active", true, null,
        null, null, 1, null, 1, now, now);
    private final AuthContext actor = new AuthContext(user, "enterprise", Set.of());
    private final TagService service = new TagService(tags, authorization, audit, pagination,
        Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void deletesTagAndRecordsSuccessAudit() {
        when(authorization.lockAndRequire(any(), anyString())).thenReturn(mock(DataScope.class));
        when(tags.find(anyString(), eq("tag"))).thenReturn(
            Optional.of(new TagView("tag", "7", now.toString(), now.toString(), "标签")));
        service.delete(actor, "tag", 7);
        verify(tags).delete(anyString(), eq("tag"), any(Instant.class));
        verify(audit).record(eq(actor.enterpriseId()), eq(actor.user()), eq("tag.delete"), eq("tag"), eq("tag"),
            eq("删除标签并解除资源标签关联"), any(Map.class));
    }

    @Test
    void recordsFailureAuditWhenTagDeleteFails() {
        when(authorization.lockAndRequire(any(), anyString())).thenReturn(mock(DataScope.class));
        when(tags.find(anyString(), eq("tag"))).thenReturn(
            Optional.of(new TagView("tag", "7", now.toString(), now.toString(), "标签")));
        doThrow(new RuntimeException("database unavailable")).when(tags).delete(anyString(), eq("tag"), any(Instant.class));
        var exception = assertThrows(RuntimeException.class, () -> service.delete(actor, "tag", 7));
        assertEquals("database unavailable", exception.getMessage());
        verify(audit).record(eq(actor.enterpriseId()), eq(actor.user()), eq("tag.delete"), eq("tag"), eq("tag"),
            eq("删除标签失败"), any(Map.class), eq(false));
    }
}
