package com.processing.apitests.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Единственное место, где задаются базовые адреса и общие заголовки.
 * Тесты получают готовую {@link RequestSpecification} и не дублируют baseUri / Content-Type.
 *
 * <p>Gateway — публичная точка входа. Остальные спецификации нужны для внутренних эндпоинтов цепочки
 * авторизации и логирования (Switch, Authorization, Card Management, Logger, Bin Lookup), которые
 * в docker-compose проброшены на хост.</p>
 */
public final class Specs {

    private Specs() {
    }

    /** Gateway: публичный контракт (docs/api-spec.md, базовый URL :8080). */
    public static RequestSpecification gateway() {
        return json(ServiceUrls.GATEWAY);
    }

    /** Card Management напрямую — минуя кэш Gateway (ResponseCaching на маршруте /api/cards/**). */
    public static RequestSpecification cardManagement() {
        return json(ServiceUrls.CARD_MANAGEMENT);
    }

    /** Switch: POST /api/internal/route. */
    public static RequestSpecification switchService() {
        return json(ServiceUrls.SWITCH);
    }

    /** Authorization: POST /api/internal/authorize, /api/internal/rollback. */
    public static RequestSpecification authorization() {
        return json(ServiceUrls.AUTHORIZATION);
    }

    /** Transaction Logger: POST /api/internal/log, поиск без Gateway. */
    public static RequestSpecification logger() {
        return json(ServiceUrls.LOGGER);
    }

    /** Bin Lookup: GET /api/bin/{bin}. */
    public static RequestSpecification binLookup() {
        return json(ServiceUrls.BIN_LOOKUP);
    }

    private static RequestSpecification json(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addHeader("X-Request-Id", TestIds.requestId())
                .log(LogDetail.URI)
                .build();
    }
}
