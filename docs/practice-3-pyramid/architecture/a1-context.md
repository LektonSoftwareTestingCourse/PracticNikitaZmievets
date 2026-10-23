# А1. C4 Level 1 — Context

> Уровень C4: **System Context**. СМП нарисована одним блоком; показано, кто находится снаружи,
> в каком направлении идёт вызов и по какому протоколу.
> Источник истины — код в [`services/`](../../../services/) и [`docker-compose.yaml`](../../../docker-compose.yaml).

```mermaid
%%{init: {"theme": "base", "themeVariables": {"background": "#0B1626", "primaryColor": "#1E3A5F", "primaryBorderColor": "#5B9BD5", "primaryTextColor": "#E8F1FB", "secondaryColor": "#16233A", "tertiaryColor": "#13203A", "textColor": "#DCE8F7", "titleColor": "#CFE0F5", "nodeTextColor": "#E8F1FB", "lineColor": "#7FA7D9", "fontSize": "14px", "clusterBkg": "#0F1E33", "clusterBorder": "#3C5A80", "edgeLabelBackground": "#13203A"}}}%%
flowchart TB
    subgraph BG[" "]
        direction TB
        T["Терминал / Terminal Simulator"]
        M["Мерчант, эквайрер / Merchant Acquirer"]
        U["Пользователь Dashboard"]
        S["СМП: процессинг, управление картами,<br/>журнал, уведомления, Dashboard"]
        B["Внешний по роли BIN Lookup /<br/>локальный учебный stub"]
        T -->|"sync HTTP JSON:<br/>POST /api/transactions"| S
        T -->|"sync HTTP: чтение<br/>/api/cards для fixture"| S
        M -->|"sync HTTP JSON:<br/>POST /api/transactions;<br/>чтение карт"| S
        S -->|"sync HTTP: запрос<br/>комиссии эквайринга"| M
        U -->|"HTTP: SPA и REST;<br/>WebSocket: журнал"| S
        S -->|"sync HTTP GET<br/>/api/bin/{bin}"| B
    end

    classDef ext fill:#14223A,stroke:#6C86A8,color:#C9D6E8,stroke-dasharray:4 3
    classDef sys fill:#24508A,stroke:#8CC0F0,color:#F2F7FD,stroke-width:2px
    class T,M,U,B ext
    class S sys

    style BG fill:#0B1626,stroke:#1E3350,color:#0B1626
```

## Описание

### Граница системы

Внутри блока «СМП» находятся контейнеры, которые обрабатывают и хранят платёжные данные:
Gateway, Switch, Authorization, Card Management, Transaction Logger, Notification Service,
backend Dashboard (nginx + SPA), PostgreSQL и RabbitMQ. Их внутреннее устройство раскрыто на
[А2](a2-container.md).

Снаружи находятся четыре актора. Terminal Simulator и Merchant Acquirer лежат в том же
репозитории, но по роли это **эмуляторы внешних участников** платёжной цепочки (терминал и
эквайрер), а не часть процессинга. BIN Lookup в стенде тоже локальный сервис, но по роли это
внешний справочник BIN, отказ которого СМП обязана переживать.

### Акторы и протоколы входа

| Актор | Направление | Протокол и точка входа | Что это значит |
|---|---|---|---|
| Terminal Simulator | → СМП | sync HTTP JSON, `POST /api/transactions` на Gateway | Основной вход авторизации (MTI 0100). Ответ (0110) приходит в том же HTTP-вызове |
| Terminal Simulator | → СМП | sync HTTP, `GET /api/cards?status=…&limit=…` | Симулятор сам подбирает карты под сценарий (normal, blocked, no money и т.д.). Запрос идёт **напрямую в Card Management**, минуя Gateway |
| Merchant Acquirer | → СМП | sync HTTP JSON, `POST /api/transactions` и `GET /api/cards` через Gateway | Второй источник транзакций, у него собственная база мерчантов и терминалов |
| СМП → Merchant Acquirer | ← из СМП | sync HTTP, `POST /api/simulator/merchant/fee` | Обратная связь: Switch во время обработки транзакции запрашивает у эквайрера комиссию. При ошибке комиссия = `null`, транзакция не прерывается |
| Пользователь Dashboard | → СМП | HTTP (SPA и REST `/api/*` через nginx → Gateway) + WebSocket `/ws/transactions` (nginx → Logger) | REST — чтение статистики и журнала по запросу; WebSocket — push новых транзакций после их сохранения в Logger |
| BIN Lookup | ← из СМП | sync HTTP, `GET /api/bin/{bin}` | Authorization обогащает запрос `issuerId`. Любая ошибка даёт `Optional.empty()`: graceful degradation, авторизация продолжается |

### Как читать стрелки

Все связи на уровне Context — **синхронные HTTP**: вызывающий ждёт ответа. Асинхронные
связи (AMQP) существуют только внутри СМП и появляются на [А2](a2-container.md).
Единственный канал с push-доставкой наружу — WebSocket к Dashboard: данные в нём появляются
**после** асинхронной записи в журнал, то есть с задержкой (eventual consistency).

## Чего не хватало в проекте

- Отдельной Context-диаграммы нет. Общая схема в [`docs/architecture.md`](../../architecture.md)
  (`graph TB`) смешивает внешних акторов и внутренние контейнеры на одном рисунке; граница
  «СМП vs внешний мир» не проведена.
- Не указаны протоколы входа для пользователя Dashboard (REST + WebSocket через nginx).
- Не отражены две связи, которые есть в коде:
  - Terminal Simulator читает карты из Card Management **напрямую**, а не через Gateway;
  - Switch **сам вызывает** Merchant Acquirer за комиссией (направление СМП → актор).
- BIN Lookup показан как обычный сервис, без пометки, что это внешний справочник с graceful degradation.

## Что сделано

- Проведена граница системы; эмуляторы и BIN Lookup вынесены наружу по их роли.
- Для каждой связи указаны направление, синхронность и конкретный endpoint.
- Добавлены две связи из кода, отсутствующие в документации (Terminal → CMS, Switch → Merchant fee).
- Каждая стрелка подтверждена ссылкой на код (таблица ниже).

## Для чего нужно в тестировании

Карта границ для E2E (модуль 7): Terminal и Merchant Simulator — это драйверы тестов
(их эмулируем или заменяем тестовым клиентом); BIN Lookup можно заглушить или выключить,
потому что его отказ не должен ломать авторизацию; Dashboard для backend-E2E не нужен,
достаточно проверить журнал через REST.

## Источники истины

| Утверждение | Код / конфигурация |
|---|---|
| Terminal → Gateway `POST /api/transactions` | [terminal-simulator `GatewayClient.java#L30`](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java#L30) |
| Terminal → CMS `GET /api/cards` напрямую | [`GatewayClient.java#L34`](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java#L34), [`application.yml#L6`](../../../services/terminal-simulator/src/main/resources/application.yml#L6), [`docker-compose.yaml#L243`](../../../docker-compose.yaml#L243) (`CARD_MGMT_URL=http://card-management:8080`) |
| Merchant → Gateway: карты и транзакции | [merchant-acquirer `GatewayClient.java#L56`](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/client/GatewayClient.java#L56), [`#L92`](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/client/GatewayClient.java#L92) |
| Switch → Merchant `POST /api/simulator/merchant/fee`, `null` при ошибке | [`MerchantAcquirerClient.java#L47`](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java#L47), [`#L59`](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java#L59); endpoint [`SimulationController.java#L121`](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/controller/SimulationController.java#L121) |
| Dashboard: `/api/` → Gateway, `/ws/` → Logger | [`dashboard/nginx.conf#L22`](../../../services/dashboard/nginx.conf#L22), [`#L29`](../../../services/dashboard/nginx.conf#L29); [`useWebSocket.ts#L19`](../../../services/dashboard/src/hooks/useWebSocket.ts#L19) |
| WebSocket `/ws/transactions` в Logger | [`WebSocketConfig.java#L31`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/websocket/WebSocketConfig.java#L31) |
| Authorization → BIN Lookup `GET /api/bin/{bin}` | [`BinLookupClient.java#L59`](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java#L59) |
| Ошибка BIN Lookup → `Optional.empty()` | [`BinLookupClient.java#L65`](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java#L65) |
| BIN Lookup — локальный stub | [`BinLookupController.java#L38`](../../../services/bin-lookup/src/main/java/com/processing/binlookup/controller/BinLookupController.java#L38) |
| Маршруты Gateway (`/api/transactions`, `/api/cards/**`, `/api/simulator/**`) | [`gateway application.yml#L64`](../../../services/gateway/src/main/resources/application.yml#L64) |
