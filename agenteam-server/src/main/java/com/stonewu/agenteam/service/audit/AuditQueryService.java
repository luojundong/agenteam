package com.stonewu.agenteam.service.audit;

import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.model.audit.request.AuditEventQuery;
import com.stonewu.agenteam.model.audit.response.AuditEventView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.http.QueryTimeRangeService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仅返回当前企业的操作审计；审计记录只追加，不随业务对象删除。
 */
@Service
public class AuditQueryService {
    private final AuditEventMapper events;
    private final EnterpriseAuthorizationService authorization;
    private final ListPagination pagination;
    private final QueryTimeRangeService ranges;

    public AuditQueryService(AuditEventMapper events, EnterpriseAuthorizationService authorization,
                            ListPagination pagination, QueryTimeRangeService ranges) {
        this.events = events;
        this.authorization = authorization;
        this.pagination = pagination;
        this.ranges = ranges;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AuditEventView> query(AuthContext actor, AuditEventQuery filter, String cursor, Integer limit) {
        authorization.require(actor, "audit.view");
        int size = pagination.limit(limit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "audit-events", filter.toString(),
            "created_desc");
        var position = pagination.read(cursor, binding);
        var range = ranges.resolve(filter.from(), filter.to(), position);
        var rows = events.query(actor.enterpriseId(), filter.actorUserId(), filter.action(), filter.objectType(),
            filter.result(), range.from(), range.to(), position == null ? null : position.time(),
            position == null ? null : position.id(), size);
        var page = pagination.page(rows, size, binding, row -> new PagePosition(row.getCreatedAt(), row.getId(),
            range.cursorValue()));
        return new PageResponse<>(page.items().stream().map(AuditEventView::from).toList(), page.nextCursor(),
            page.hasMore());
    }
}
