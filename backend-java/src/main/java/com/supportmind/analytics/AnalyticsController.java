package com.supportmind.analytics;

import com.supportmind.analytics.AnalyticsDtos.AnalyticsResponse;
import com.supportmind.analytics.AnalyticsDtos.DailyMessages;
import com.supportmind.analytics.AnalyticsDtos.DateRange;
import com.supportmind.analytics.AnalyticsDtos.Overview;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.exception.ApiException;
import com.supportmind.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics", description = "Business metrics for supervisors")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
public class AnalyticsController {

    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 366;

    private final AnalyticsService analyticsService;
    private final Clock clock;

    public AnalyticsController(AnalyticsService analyticsService, Clock clock) {
        this.analyticsService = analyticsService;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "All dashboard metrics for a date range (UTC, default: last 30 days)")
    public AnalyticsResponse all(@AuthenticationPrincipal AuthenticatedUser user,
                                 @Parameter(example = "2026-09-01") @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @Parameter(example = "2026-09-30") @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.compute(user.organizationId(), range(from, to));
    }

    @GetMapping("/overview")
    @Operation(summary = "Totals, response time, AI resolution and handoff rates, average AI confidence")
    public Overview overview(@AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return all(user, from, to).overview();
    }

    @GetMapping("/messages-per-day")
    @Operation(summary = "Messages per day by sender (customer, AI, agent)")
    public List<DailyMessages> messagesPerDay(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return all(user, from, to).messagesPerDay();
    }

    @GetMapping("/tickets-by-priority")
    @Operation(summary = "Tickets created in the range by priority")
    public Map<String, Long> ticketsByPriority(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return all(user, from, to).ticketsByPriority();
    }

    @GetMapping("/tickets-by-category")
    @Operation(summary = "Tickets created in the range by category")
    public Map<String, Long> ticketsByCategory(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return all(user, from, to).ticketsByCategory();
    }

    private DateRange range(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The range cannot exceed " + MAX_DAYS + " days");
        }
        return new DateRange(start, end);
    }
}
