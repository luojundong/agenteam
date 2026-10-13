package com.stonewu.agenteam.controller.audit;

import com.stonewu.agenteam.model.audit.request.AuditEventQuery;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.audit.response.AuditEventView;
import com.stonewu.agenteam.service.audit.AuditQueryService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端操作审计查询；需 {@code audit.view} 权限。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/audit-events")
public class AuditEventApiController {

    private final AuditQueryService queries;

    private final AuthContextService identity;

    private final ApiResponses responses;

    public AuditEventApiController(AuditQueryService queries, AuthContextService identity, ApiResponses responses) {
        this.queries = queries;
        this.identity = identity;
        this.responses = responses;
    }

    @GetMapping
    public Object list(@PathVariable String enterpriseId, @RequestParam(required = false) String action,
                      @RequestParam(required = false) String objectType, @RequestParam(required = false) String actorUserId,
                      @RequestParam(required = false) String result, @RequestParam(required = false) String from,
                      @RequestParam(required = false) String to, @RequestParam(required = false) String cursor,
                      @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        return responses.success(
            queries.query(identity.requireEnterprise(request.getSession(false), enterpriseId),
                new AuditEventQuery(from, to, actorUserId, action, objectType, result), cursor, limit), request);
    }
}
