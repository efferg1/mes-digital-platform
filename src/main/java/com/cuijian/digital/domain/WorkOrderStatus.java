package com.cuijian.digital.domain;

/**
 * 工单状态机。
 *
 * CREATED ──release──> RELEASED ──report──> IN_PROGRESS ──report(满量)──> FINISHED
 *    └──────────────── cancel ───────────────> CANCELLED
 *
 * 状态流转集中在业务层判断，不散落在 SQL 里。
 */
public final class WorkOrderStatus {

    public static final String CREATED = "CREATED";
    public static final String RELEASED = "RELEASED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String FINISHED = "FINISHED";
    public static final String CANCELLED = "CANCELLED";

    private WorkOrderStatus() {
    }

    /** 只有这两种状态允许报工 */
    public static boolean reportable(String status) {
        return RELEASED.equals(status) || IN_PROGRESS.equals(status);
    }

    /** 只有 CREATED 允许下达 */
    public static boolean releasable(String status) {
        return CREATED.equals(status);
    }

    /** 已完工或已取消的单子不能再动 */
    public static boolean closed(String status) {
        return FINISHED.equals(status) || CANCELLED.equals(status);
    }
}
