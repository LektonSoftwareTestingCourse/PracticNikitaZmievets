package com.processing.apitests.support;

/**
 * Адреса сервисов СМП. Значение берётся из system property (передаётся surefire из pom.xml),
 * затем из переменной окружения, затем — порт по умолчанию из .env.example / docs/architecture.md.
 */
public final class ServiceUrls {

    public static final String GATEWAY = resolve("gateway.url", "GATEWAY_URL", "http://localhost:8080");
    public static final String CARD_MANAGEMENT = resolve("card-management.url", "CARD_MGMT_URL", "http://localhost:8081");
    public static final String SWITCH = resolve("switch.url", "SWITCH_URL", "http://localhost:8082");
    public static final String AUTHORIZATION = resolve("authorization.url", "AUTH_URL", "http://localhost:8083");
    public static final String MERCHANT = resolve("merchant.url", "MERCHANT_URL", "http://localhost:8084");
    public static final String TERMINAL = resolve("terminal.url", "TERMINAL_URL", "http://localhost:8085");
    public static final String LOGGER = resolve("logger.url", "LOGGER_URL", "http://localhost:8088");
    public static final String BIN_LOOKUP = resolve("bin-lookup.url", "BIN_LOOKUP_URL", "http://localhost:8096");

    private ServiceUrls() {
    }

    private static String resolve(String property, String env, String defaultValue) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return defaultValue;
    }
}
