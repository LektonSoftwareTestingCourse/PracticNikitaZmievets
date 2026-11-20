package com.processing.apitests.support;

/**
 * Пути эндпоинтов. Источники перечня: docs/api-spec.md, docs/api/openapi.yaml,
 * services/gateway/src/main/resources/application.yml (маршруты) и контроллеры сервисов.
 */
public final class Endpoints {

    // Gateway (публичные)
    public static final String HEALTH = "/health";
    public static final String TRANSACTIONS = "/api/transactions";
    public static final String TRANSACTIONS_SEARCH = "/api/transactions/search";
    public static final String TRANSACTIONS_EXPORT = "/api/transactions/export";
    public static final String DASHBOARD_STATS = "/api/dashboard/stats";
    public static final String DASHBOARD_RECENT = "/api/dashboard/recent";
    public static final String DASHBOARD_CHARTS = "/api/dashboard/charts";
    public static final String TERMINAL_RUN = "/api/simulator/terminal/run";
    public static final String TERMINAL_START = "/api/simulator/terminal/start";
    public static final String TERMINAL_STATUS = "/api/simulator/terminal/status";
    public static final String MERCHANT_RUN = "/api/simulator/merchant/run";
    public static final String MERCHANT_FEE = "/api/simulator/merchant/fee";
    public static final String MERCHANTS = "/api/simulator/merchants";

    // Card Management (через Gateway: маршрут /api/cards/**)
    public static final String CARDS = "/api/cards";
    public static final String CARD = "/api/cards/{pan}";
    public static final String CARDS_GENERATE = "/api/cards/generate";
    public static final String CARDS_BULK_UPDATE = "/api/cards/bulk-update";
    public static final String CARD_RESERVE = "/api/cards/{pan}/reserve";
    public static final String CARD_ROLLBACK = "/api/cards/{pan}/rollback";
    public static final String BINS = "/api/bins";
    public static final String BIN = "/api/bins/{bin}";

    // Внутренние эндпоинты цепочки авторизации и логирования
    public static final String INTERNAL_ROUTE = "/api/internal/route";
    public static final String INTERNAL_AUTHORIZE = "/api/internal/authorize";
    public static final String INTERNAL_ROLLBACK = "/api/internal/rollback";
    public static final String INTERNAL_LOG = "/api/internal/log";
    public static final String BIN_LOOKUP = "/api/bin/{bin}";

    private Endpoints() {
    }
}
