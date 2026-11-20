# API-тесты СМП — отчёт

**Модуль:** `services/api-tests` (Java 21, Maven, JUnit 5, REST-assured 5.4.0, Hamcrest, Awaitility 4.2.2, JSON Schema Validator).
**Штатный прогон:** 288 проверок, все зелёные; 3 последовательных прогона без падений (раздел 6).
**Известные дефекты:** 30 проверок с тегом `known-defect` утверждают контракт там, где реализация его нарушает. Все красные, из штатного прогона исключены; запускаются вручную (раздел 7).

Ожидания взяты из `docs/api-spec.md`, `docs/api/openapi.yaml`, `tz/02`, `tz/04`, `tz/05`, `tz/08`, `docs/architecture.md`, `docs/practice-2/test-design.md` (классы КЭ-xx и границы ГЗ-xx) и кода контроллеров. Источник ожидания указан в `@DisplayName` или в Javadoc каждого класса и теста.

## 0. Перечень эндпоинтов: три источника

Источники: (A) `docs/api-spec.md`, (B) `docs/api/openapi.yaml`, (C) маршруты Gateway в `services/gateway/src/main/resources/application.yml`, (D) контроллеры сервисов. Внутренние эндпоинты цепочки авторизации и логирования взяты из `docs/architecture.md` и клиентов в коде (Switch → Authorization → Card Management / Bin Lookup, Switch → RabbitMQ/Logger).

| Эндпоинт | A | B | C (маршрут Gateway) | D | Расхождение |
|---|:-:|:-:|:-:|:-:|---|
| `GET /health` (Gateway) | ✓ | ✓ | контроллер Gateway | ✓ | — |
| `POST /api/cards` | ✓ | ✓ | cards | ✓ | A: «409 — PAN уже существует», но PAN генерирует сервер, ответ недостижим |
| `GET /api/cards` (список) | — | ✓ | cards | ✓ | нет в A |
| `POST /api/cards/generate` | ✓ | ✓ | cards | ✓ | — |
| `GET /api/cards/{pan}` | ✓ | ✓ | cards | ✓ | — |
| `PATCH /api/cards/{pan}` | — | ✓ | cards | ✓ | нет в A |
| `DELETE /api/cards/{pan}` | — | ✓ | cards | ✓ | нет в A |
| `POST /api/cards/{pan}/reserve` | — | ✓ | cards (**публично**) | ✓ | внутренняя операция открыта через Gateway — DISC-29 |
| `POST /api/cards/{pan}/rollback` | — | ✓ | cards (**публично**) | ✓ | то же — DISC-29 |
| `POST /api/cards/bulk-update` | — | — | cards | ✓ | есть только в коде |
| `GET/POST /api/bins`, `GET/DELETE /api/bins/{bin}` | — | — | — | ✓ (CMS) | только в коде, Gateway не маршрутизирует — DISC-21 |
| `POST /api/transactions` | ✓ | ✓ | switch (`RewritePath → /api/internal/route`) | ✓ | — |
| `GET /api/transactions/search` | ✓ | ✓ | logger | ✓ | A не упоминает `issuerId`, `mcc` (есть в B, D) |
| `GET /api/transactions/export` | — | ✓ | logger | ✓ | нет в A |
| `POST /api/simulator/terminal/run` | ✓ | ✓ | terminal | ✓ | в D обязателен `tps` — DISC-22 |
| `POST /api/simulator/terminal/start`, `GET …/status` | — | ✓ | terminal | — | не реализованы — DISC-23 |
| `POST /api/simulator/terminal/start-continuous`, `…/stop` | — | — | terminal | ✓ | только в коде — DISC-23 |
| `POST /api/simulator/merchant/run` | ✓ | ✓ | merchant | ✓ | — |
| `POST /api/simulator/merchant/fee` | — | — | merchant | ✓ | только в коде |
| `GET /api/simulator/merchants` | — | ✓ | **нет** | ✓ | через Gateway 404 — DISC-20 |
| `GET /api/dashboard/stats`, `/recent` | ✓ | ✓ | dashboard | ✓ | — |
| `GET /api/dashboard/charts` | — | ✓ | dashboard | ✓ | нет в A |
| `WS /ws/transactions` | ✓ | ✓ | — | ✓ (Logger) | не HTTP-контракт, вне покрытия (раздел 5) |
| `POST /api/internal/route` (Switch) | ✓ | ✓ | через rewrite | ✓ | — |
| `POST /api/internal/authorize` (Authorization) | ✓ | ✓ | — | ✓ | B: только 200; D: 200/400/403/404/422/503 — DISC-17 |
| `POST /api/internal/rollback` (Authorization) | — | — | — | ✓ | только в коде и `architecture.md` (reversal) |
| `POST /api/internal/log` (Logger) | ✓ | ✓ | — | ✓ | тело 200 расходится с B — DISC-16 |
| `GET /api/bin/{bin}` (Bin Lookup) | — | — | — | ✓ | только `architecture.md` (вариант B) |
| `GET /health` Switch / Authorization / CMS / Logger | ТЗ | — | — | ✓ | — |
| `/switch-docs`, `/cards-docs` и т.п. (Swagger) | — | — | ✓ | ✓ | служебные, вне покрытия |
| `GET /api/notifications` (Notification Service) | — | — | — | ✓ | асинхронная цепочка карточных событий, модуль 6 |

Ещё одно расхождение источников: блок `servers` в B называет порты, противоречащие `architecture.md` и `.env.example` (Switch 8081 вместо 8082, Authorization 8082 вместо 8083, Card Management 8083 вместо 8081 и т.д.) — DISC-25.

## 1. Покрытие

Количество — число выполняемых проверок (вызовов параметризованных тестов) в штатном прогоне; в скобках указаны проверки `known-defect`. Эндпоинт используется и в других тестах для подготовки данных или верификации — здесь это не считается.

| Эндпоинт | Проверяемые статус-коды | Кол-во проверок | Статус |
|---|---|:-:|---|
| `GET /health` (Gateway) | 200 | 1 (+1) | ✅ (503 вне покрытия) |
| Маршрутизация Gateway: неизвестный путь, `GET /api/transactions`, `X-Request-Id` | 404, 200 | 4 | ✅ |
| `POST /api/transactions` | 200 (APPROVED и 6 исходов отказа), 400, 429 | 67 (+7) | ✅ (503 вне покрытия) |
| `GET /api/transactions/search` | 200, 400 | 43 (+1) | ✅ |
| `GET /api/transactions/export` | 200 | 1 | ✅ |
| `GET /api/dashboard/stats` | 200 | 2 | ✅ |
| `GET /api/dashboard/recent` | 200, 400 | 6 | ✅ |
| `GET /api/dashboard/charts` | 200, 400 | 5 | ✅ |
| `POST /api/simulator/terminal/run` | 200, 400 | 8 (+1) | ✅ |
| `POST /api/simulator/terminal/start-continuous`, `/stop` | 200, 400, 422 | 4 | ✅ |
| `POST /api/simulator/terminal/start`, `GET /status` | — | (+2) | ❌ не реализованы |
| `POST /api/simulator/merchant/run` | 200, 400, 404 | 9 | ✅ (502 вне покрытия) |
| `POST /api/simulator/merchant/fee` | 400, 404 | 1 | ⚠️ 200 не покрыт (раздел 5) |
| `GET /api/simulator/merchants` | 200 (напрямую) | 1 (+1) | ⚠️ через Gateway 404 |
| `POST /api/cards` | 201, 400, 404 | 24 (+6) | ✅ |
| `GET /api/cards/{pan}` (с кэшем Gateway) | 200 (X-Cache MISS/HIT), 400, 404 | 6 (+1) | ✅ |
| `GET /api/cards` | 200, 400, 413 | 11 (+2) | ✅ |
| `PATCH /api/cards/{pan}` | 200, 400, 404 | 10 (+1) | ✅ |
| `DELETE /api/cards/{pan}` | 204, 404 | 2 | ✅ |
| `POST /api/cards/bulk-update` | 200, 400 | 2 | ✅ |
| `POST /api/cards/generate` | 201, 400, 404 | 7 | ✅ |
| `POST /api/cards/{pan}/reserve` | 200, 400, 402, 404, 409 | 10 (+1) | ✅ |
| `POST /api/cards/{pan}/rollback` | 200, 404, 409 | 2 (+2) | ✅ |
| `/api/bins` (CMS) | 200, 201, 204, 400, 404, 409 | 6 (+3) | ✅ |
| `POST /api/internal/route` (Switch) | 200 (в т.ч. отказы самого Switch) | 6 | ✅ |
| `POST /api/internal/authorize` | 200, 400, 403, 404, 422 | 10 | ✅ (503 вне покрытия) |
| `POST /api/internal/rollback` | 200, 400, 404, 409 | 6 | ✅ (503 вне покрытия) |
| `POST /api/internal/log` | 200, 201, 400, 409 | 23 (+1) | ✅ (503 вне покрытия) |
| `GET /api/bin/{bin}` | 200, 404, 500 | 7 | ✅ |
| `GET /health` Switch, Authorization, CMS, Logger | 200 | 4 | ✅ |
| **Итого** | | **288 (+30)** | |

Классы тестов: `gateway/GatewayApiTest`, `transactions/{TransactionValidation, AuthorizationOutcome, TransactionJournal, TransactionSearch}ApiTest`, `cards/{CardCreate, CardQuery, CardUpdate, CardReservation, CardGenerate, BinIssuer}ApiTest`, `internal/{AuthorizationInternal, SwitchRoute, LoggerRedelivery, BinLookup, ServiceHealth}ApiTest`, `dashboard/DashboardApiTest`, `simulator/SimulatorApiTest`.

## 2. Бизнес-отказы

Код и причина всегда проверяются вместе: у `CARD_BLOCKED` и `CARD_INACTIVE` одинаковый код 05, у `CARD_NOT_FOUND` от Switch и от Authorization совпадают и код, и причина. Каждая проверка создаёт свою карту через `POST /api/cards` и явно задаёт баланс, лимиты и статус. Отказ не должен менять баланс — это проверяется чтением карты из Card Management напрямую.

| Исход | Код | Причина | Путь достижения | Способ подготовки данных | Статус |
|---|:-:|---|---|---|---|
| Одобрено | 00 | — (`declineReason: null`) | норма | карта с балансом ≥ суммы; rrn `^\d{12}$`, authCode `^[A-Z0-9]{6}$`, баланс −сумма | ✅ |
| Карта не найдена (Authorization) | 14 | `CARD_NOT_FOUND` | данные запроса: PAN известного BIN, карты нет | случайный PAN с BIN 400000; признак Authorization — `mti=0100` | ✅ |
| Карта не найдена (Switch) | 14 | `CARD_NOT_FOUND` | данные запроса: BIN вне таблицы маршрутизации | PAN с BIN 999999; признак Switch — `mti=0110`, `processingTimeMs=0` | ✅ |
| Карта не найдена (удалена) | 14 | `CARD_NOT_FOUND` | состояние карты: soft delete | `DELETE /api/cards/{pan}` | ✅ |
| Карта заблокирована | 05 | `CARD_BLOCKED` | состояние карты | `PATCH status=BLOCKED` | ✅ |
| Карта неактивна | 05 | `CARD_INACTIVE` | состояние карты | `PATCH status=INACTIVE` | ✅ |
| Срок истёк (статус) | 54 | `CARD_EXPIRED` | состояние карты | `PATCH status=EXPIRED` | ✅ |
| Срок истёк (дата) | 54 | `CARD_EXPIRED` | данные запроса: `transmissionDateTime` — 1-е число месяца после срока | срок карты +3 года; граница: последняя секунда месяца срока → 00 | ✅ |
| Недостаточно средств | 51 | `INSUFFICIENT_FUNDS` | данные запроса: сумма = баланс + 1 | карта с балансом A; граница: сумма = A → 00, остаток 0 | ✅ |
| Недостаточно средств | 51 | `INSUFFICIENT_FUNDS` | состояние карты: баланс снижен до A − 1 | `PATCH availableBalance` | ✅ |
| Превышен лимит (дневной) | 61 | `EXCEEDS_AMOUNT_LIMIT` | состояние карты: `dailyLimit = A − 1` | создание карты с лимитом; граница: сумма = лимит → 00 | ✅ |
| Превышен лимит (накопление за день) | 61 | `EXCEEDS_AMOUNT_LIMIT` | данные запроса: 600 + 401 при лимите 1000 | серия запросов в один день; остаток ровно 400 → 00 | ✅ |
| Превышен лимит (месячный) | 61 | `EXCEEDS_AMOUNT_LIMIT` | данные запроса: траты в разные дни одного месяца | дневной 1000 / месячный 1500, фиксированный март 2026; 1000 + 501 → 61, 1000 + 500 → 00 | ✅ |
| Authorization недоступен (с точки зрения Switch) | 05 | `Authorization service unavailable` | данные запроса: `stan` из 5 цифр — Authorization отвечает 400, Switch трижды повторяет запрос и отказывает | прямой вызов Switch; один вызов на прогон, чтобы не открыть circuit breaker Switch | ✅ |
| Системная ошибка / CMS / БД недоступны | 96 | `SERVICE_UNAVAILABLE`, `DB_UNAVAILABLE`, `RESERVATION_FAILED`, `System error` | отказ инфраструктуры | — | вне покрытия (модуль 6) |
| Неизвестная причина | 05 | `UNKNOWN_REASON` | исключение вне ожидаемых | — | вне покрытия: недостижим через API |
| Потеряна / украдена карта | 41 / 43 | — | — | — | в api-spec есть, в реализации нет (раздел 4) |

**Почему два пути — это разные проверки.** Путь «состояние карты» проверяет, что Authorization читает актуальные данные карты из Card Management и правильно сопоставляет статус с исходом (ветка `switch (CardModelStatus)` в `AuthServiceImpl`, шаг 2). Путь «данные запроса» проверяет логику сравнения с полями запроса: арифметику дат (`expiryDate.atEndOfMonth()` против `transmissionDateTime`, шаг 3), сравнение сумм и накопление в `limit_usage` по дню и месяцу (шаги 4–6). Это разные ветки кода. Для `CARD_EXPIRED` исход один, но условия независимы: ошибка в сравнении дат не ломает ветку статуса, и наоборот. Для `CARD_NOT_FOUND` три пути проходят через три разных компонента — таблицу BIN Switch, 404 Card Management и фильтрацию удалённых карт. Поэтому по совпадающим коду и причине нельзя понять, кто отказал. Это различают по `mti` и `processingTimeMs`, а в журнале — по `issuerId`: у отказа Switch он пуст.

## 3. Валидация

Каждый тест нарушает ровно одно правило, остальные поля валидны. Для каждого нарушения проверяются статус, код ошибки, текст с именем поля и правилом и `serviceName` — какой сервис отказал.

| Поле | Правило | Классы | Границы | Кол-во проверок |
|---|---|---|---|:-:|
| `POST /api/transactions`: 10 строковых полей + `amount`, `transmissionDateTime` | обязательность (tz/02 §2) | отсутствует; строка из пробелов | — | 20 |
| `terminalType` | необязателен | не передан → 200 | — | 1 |
| `mti` | = "0100" | 0200, 0400, 0110; длина 3 и 5 | 100 / 01000 | 5 |
| `pan` | ровно 16 цифр | не цифры, пробелы | 15 / 17 | 4 |
| `amount` | > 0 | отрицательное, не число | 0, −1 (OFF); 1 (ON) → 200 | 5 |
| `currencyCode` | 3 символа | — | 1, 2, 4 | 3 |
| `mcc` | 4 цифры | не цифры | 3 / 5 | 3 |
| тело / `transmissionDateTime` | валидный JSON, ISO-8601 | синтаксис, пустое тело, массив, неверная дата | — | 4 |
| `stan`, `currencyCode` (буквы), `terminalType` (enum), `amount` (дробный) | правила openapi, не проверяемые Gateway | — | — | (+4) known-defect |
| `POST /api/cards`: `bin` | 6 цифр, обязателен, из справочника | не цифры; отсутствует; 999999 → 404 | 5 / 7 | 5 |
| `cardholderName` | `^[A-Z\s\-.']+$`, длина ≤ 255 | строчные, `_`, кириллица, цифры | 1, 255 (ON); 256 (OFF) | 6 (+1) |
| `currencyCode` (карта) | 3 цифры | буквы | 2 / 4 | 3 |
| `dailyLimit`, `monthlyLimit` | ≥ 0 | — | −1 (OFF); 0 (ON) | 3 |
| `monthlyLimit ≥ dailyLimit`, `initialBalance ≥ 0`, умолчания | openapi / PATCH | — | 4 999 999 при дневном 5 000 000 | (+3) known-defect |
| `PATCH`: лимиты | ≥ 0; дневной ≤ месячного | — | равенство (ON) → 200; +1 (OFF) → 400 | 4 (+1) |
| `GET /api/cards/{pan}` | 16 цифр | буквы | 15 / 17 | 3 |
| `GET /api/cards`: `limit`, `offset`, `bin` | ≥ 1, ≥ 0, 6 цифр, `limit ≤ 10000` | — | 0, −1, 10001 | 4 |
| `generate`: `count`, `bins` | 1..10000, непустой, 6 цифр | `[]`, BIN из 3 цифр | 0 / 10001; 1 (ON) | 5 |
| `reserve`: `amount`, `rrn` | ≥ 1, 12 цифр | — | 0, −1; 1 и баланс (ON); баланс + 1 → 402 | 7 |
| `rollback` (Authorization): `rrn`, `pan`, `amount` | 12 / 16 цифр, > 0 | — | amount 0 | 3 |
| `authorize` (Authorization): `stan`, `processingCode`, `issuerId` | `@ExactSize`, `@NotBlank` | — | 5 символов вместо 6 | 3 |
| `POST /api/internal/log` | обязательность (7 полей), формат `stan`/`pan`/`mcc`/`currencyCode`, `acquirerId ≤ 10`, `amount > 0`, `status ∈ enum` | — | `acquirerId` 11 символов | 15 |
| Поиск: `limit`, `offset`, `status`, `dateFrom/To` | 1..500, ≥ 0, enum, ISO-дата, `dateFrom ≤ dateTo` | неверный enum, формат даты, не число | 0 / 501 / −1; 1 и 500 (ON) | 10 (+1) |
| Dashboard: `limit` (recent), `granularity`, `from ≤ to` | 1..500, hour/day | `week`, `HOUR`, `minute` | 0 / 501; 1 и 500 (ON) | 8 |
| Симуляторы: `count`, `scenario`, `tps`, `transactionType` | ≥ 1, enum, ≥ 1 | вне enum, отсутствует | 0 | 9 (+1) |
| `/api/bins`: `bin` | 6 цифр | не цифры | 5 / 7 | 3 (+3) |

## 4. Расхождения контракта и реализации

Классификация: **Дефект** — источник декларации верен, реализацию нужно исправить; тест утверждает контракт и помечен `known-defect`. **Ограничение** — расхождение признано допустимым с обоснованием; тест фиксирует наблюдаемое поведение со ссылкой на номер DISC. Все расхождения проверены экспериментально на поднятом стеке.

| # | Декларировано | Наблюдается | Источник декларации | Классификация |
|---|---|---|---|---|
| DISC-01 | Ни одна транзакция не будет APPROVED без записи в журнал | APPROVED с `acquirerId` длиной 11 символов: средства зарезервированы, Logger падает на `varchar(10)`, записи нет | architecture.md «Publisher Confirms» | **Дефект, критичный**: потеря финансовой записи |
| DISC-02 | `mti` ответа = "0110" | Authorization возвращает `mti` запроса ("0100"); Switch в своих отказах — "0110" | api-spec §3, модель AuthorizationResponse | Дефект, средний: потребитель не может опираться на MTI |
| DISC-03 | `expiryDate` — String(4) MMYY ("1228") | `"2029-11"` (YearMonth ISO) | api-spec, модель Card; openapi `^\d{4}$` | Дефект, средний: ломает разбор у клиента |
| DISC-04 | Обязательны только `bin`, `cardholderName`; у остальных есть умолчания | без `currencyCode` → 400 «must not be blank», без лимитов → умолчания не применяются | openapi CardCreateRequest | Дефект, средний |
| DISC-05 | Кэш Gateway отдаёт актуальные данные | После транзакции `GET /api/cards/{pan}` через Gateway отдаёт старый баланс (`X-Cache: HIT`): резерв идёт в обход Gateway, кэш не сбрасывается | tz/02 «кэширование ответов CMS» + api-spec (карта отражает баланс) | **Дефект, высокий**: клиент видит неверный баланс |
| DISC-06 | `limit` списка карт по умолчанию 50 | 10 (`defaultValue="10"` в контроллере) | openapi, `app.card-service.defaults.page-limit=50` | Дефект, низкий |
| DISC-07 | `stan` — 6 цифр, Gateway валидирует обязательные поля | Gateway пропускает `stan="12345"`. Authorization отвечает 400, Switch трижды повторяет запрос (~3 с) и возвращает `DECLINED/05 «Authorization service unavailable»`. Ошибка клиента считается отказом в circuit breaker Switch | api-spec (String(6)), openapi `^\d{6}$`, tz/02 §2 | **Дефект, высокий**: ошибка клиента маскируется под недоступность; поток таких запросов открывает circuit breaker для всех |
| DISC-08 | `currencyCode` — `^\d{3}$` | "RUB" одобряется | openapi (tz/02: «3 символа» — источники расходятся) | Дефект, низкий; верным считаем openapi (код валюты ISO 4217 числовой) |
| DISC-09 | `terminalType ∈ {POS, ATM, ECOM}` | "KIOSK" одобряется | api-spec, openapi enum | Дефект, низкий |
| DISC-10 | `amount` — integer (копейки) | 0.5 одобряется; `totalAmount` в статистике становится дробным | openapi `amount: integer`, api-spec «суммы в минорных единицах» | Дефект, средний |
| DISC-11 | `monthlyLimit ≥ dailyLimit`, баланс ≥ 0 (PATCH это проверяет) | при создании карты 201 с месячным < дневного и с балансом −5 | Card.withData (тот же сервис), test-design R-CMS-11 | Дефект, средний: несогласованность создания и изменения |
| DISC-12 | Лимиты (шаги 4–5) проверяются до баланса (шаг 6) | баланс проверяется раньше: при Б = A−1 и лимите A−1 → 51 вместо 61 | tz/04 §2 | Дефект, низкий: меняется код отказа |
| DISC-13 | Откат возвращает зарезервированную сумму | `rollback` принимает любую сумму: резерв 100, откат 500 → баланс выше исходного | architecture.md (reversal), смысл операции | **Дефект, критичный**: создание денег |
| DISC-14 | Тело ошибки называет поле и правило | `dateFrom > dateTo` → 400 с `message: ""` | Требование задания, `@ValidDateRange.message` | Дефект, низкий |
| DISC-15 | `pan` / `mcc` в поиске — по шаблону | `pan=abc` → 200, пустая выборка | openapi search parameters | Ограничение: результат корректен (ничего не найдено), вреда потребителю нет |
| DISC-16 | Повтор `POST /api/internal/log` → 200 `{id, status: "already_exists"}` | 200 с полной записью Transaction | openapi LogResponse | Дефект, средний для REST-клиента; Switch пишет через RabbitMQ, поэтому сейчас не проявляется |
| DISC-17 | `POST /api/internal/authorize` → 200 с решением в теле | 403 / 404 / 422 / 400 / 503 в зависимости от причины (превышение лимита → 400) | openapi | Ограничение: внутренний контракт, Switch читает тело при любом статусе; зафиксировано тестами `AuthorizationInternalApiTest` |
| DISC-18 | Неверный параметр → 400 | `limit > 10000` → 413 Payload Too Large | openapi `/api/cards` GET | Ограничение, низкий: статус семантически неточен, тело информативно |
| DISC-19 | Нечитаемое тело / неверный enum → 400 | 500 (`HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` попадают в общий обработчик) в CMS | общие правила REST, openapi 400 | Дефект, средний: клиентская ошибка видна как сбой сервера |
| DISC-20 | `GET /api/simulator/merchants` через Gateway | 404: маршрут `merchant` покрывает только `/api/simulator/merchant/**` | openapi (server = Gateway) | Дефект, низкий |
| DISC-21 | `issuerId` — `[A-Z0-9]{6,10}` | любые значения → 201: у аннотации `@IssuerId` нет `@Constraint`; эндпоинт не описан в спецификации | common `IssuerIdValidator` | Дефект, средний: портит справочник BIN |
| DISC-22 | Тело `{count, scenario}` | без `tps` → 400 (пример из README и api-spec не работает) | api-spec §4, openapi | **Дефект, высокий** для потребителя |
| DISC-23 | `POST /terminal/start` → 202, `GET /terminal/status` → 200 | 500 «No static resource» (даже не 404); вместо них реализованы `start-continuous` / `stop` | openapi | Дефект, средний |
| DISC-24 | `X-Request-Id` в каждом ответе | есть только на проксируемых маршрутах, на `/health` нет | tz/02 §6 | Дефект, низкий |
| DISC-25 | Порты сервисов | блок `servers` в openapi противоречит architecture.md и .env | openapi vs architecture.md | Дефект документации |
| DISC-26 | Единый ErrorResponse `{error, message, timestamp}`, `retryAfterMs: integer` | CMS кладёт в `error` имя Java-исключения; Authorization отдаёт стандартное тело Spring без `message`; Logger — `retryAfterMs: "0"` (строка) | openapi ErrorResponse | Ограничение: тесты принимают фактические форматы; для потребителя — разнородность обработки ошибок |
| DISC-27 | Тело 429 — ErrorResponse с `timestamp` | `{error, message, retryAfterMs}` | openapi vs tz/02 §4 (пример без timestamp) | Ограничение: верным считаем tz/02, тест фиксирует 3 поля |
| DISC-28 | Имя держателя > 255 → понятная ошибка валидации | 400 `OutOfRetriesException` «PanCollisionException …» (падает ограничение БД, сервис 3 раза повторяет вставку) | openapi `maxLength: 255` | Дефект, низкий: неверная диагностика |
| DISC-29 | reserve / rollback — внутренние операции Authorization | доступны через публичный Gateway (`/api/cards/**`); вместе с DISC-13 любой клиент может поднять баланс карты (проверено: баланс 1 000 → 100 990) | architecture.md (последовательность вызовов), tz/02 §3 | **Дефект, критичный (безопасность)** |
| DISC-30 | Коды 41 Lost Card, 43 Stolen Card | не реализованы (нет статусов и причин) | api-spec «Коды ответов» | Ограничение: в модели карты нет таких статусов; оставлено как справочная информация спецификации |

## 5. Обоснование достаточности

**Как выделены классы.** Для каждого эндпоинта входные данные разбиты по трём осям.

1. *Синтаксис и формат* — по одному классу на правило из спецификации или кода: обязательность, длина, алфавит, диапазон, enum. Для числовых и длинных полей — границы ON/OFF.
2. *Состояние системы* — статус карты (4 значения + удалена), баланс относительно суммы, остатки дневного и месячного лимитов, срок действия относительно даты транзакции. Классы КЭ-01…КЭ-29 и границы ГЗ-01…ГЗ-07 перенесены из `docs/practice-2/test-design.md`.
3. *Повтор и последовательность* — первая доставка, повтор с идентичными данными, повтор с другим `createdAt`, совпадение ключа при расхождении данных (6 разных полей), повторный reserve и rollback, накопление лимитов.

**Чем подтверждена полнота.**
- *Эндпоинты* — сверкой четырёх источников (раздел 0): каждый эндпоинт из любого источника либо покрыт, либо явно вынесен за рамки.
- *Статус-коды* — по коду обработки: `@ExceptionHandler` каждого `GlobalExceptionHandler`, `switch` в `AuthControllerImpl`, `onStatus` в клиентах. Каждый статус, достижимый без отказа инфраструктуры, имеет проверку (раздел 1).
- *Исходы авторизации* — перечислены по `DeclineOutcome.java` и фабрикам `AuthorizationResponse`. Каждый исход, достижимый через API, покрыт; недостижимые объяснены (раздел 2).
- *Валидация* — каждое правило Gateway (`TransactionRequestValidator`), каждая аннотация DTO (`CreateCardRequest`, `ReserveRequest`, `RollbackRequest`, `TransactionRequest`, `TransactionFilter`, `GenerateCardsRequest`) и ручные проверки сервисов (`Card.withData`, `bulkUpdateStatus`) имеют хотя бы один тест на класс.
- *Комбинации фильтров поиска* — ортогональный массив L9(3⁴): 4 параметра (`status`, `mcc`, `issuerId`, диапазон дат) × 3 значения (значение / другое значение / не задан). Все 54 пары значений покрыты 9 строками вместо 81. Ожидаемое количество считает оракул по тому же набору данных, а не берётся из ответа.
- *Изоляция данных* — каждый тест создаёт свои карты, STAN, RRN и merchantId. Поиск ограничен уникальным merchantId прогона, список карт — BIN + `startDate` прогона. Три последовательных прогона на одной БД зелёные, значит тесты не зависят от данных прошлых прогонов.

**Что осознанно оставлено вне покрытия и почему.**
- *503 / 502 и коды 96 (`SERVICE_UNAVAILABLE`, `DB_UNAVAILABLE`, `RESERVATION_FAILED`, откат при nack брокера)* — нужна инъекция отказов (остановка контейнеров, БД, RabbitMQ). Это модуль 6 (интеграционные тесты с контейнерами). Остановка сервисов внутри API-прогона сделала бы набор зависимым от порядка запуска.
- *`UNKNOWN_REASON`* — достигается только непредусмотренным исключением, через API не воспроизводится.
- *Circuit breaker Gateway и Switch, graceful shutdown* — требуют отказов downstream-сервисов (модуль 6). Отдельно учтено, что тест DISC-07 не должен открывать circuit breaker Switch: такой вызов один на прогон, а в штатном наборе тест на Gateway исключён.
- *`WS /ws/transactions`* — не синхронный HTTP-контракт.
- *`/api/notifications`* — асинхронная цепочка карточных событий через outbox и RabbitMQ (модуль 6).
- *`POST /api/simulator/merchant/fee` → 200* — комиссия сохраняется только для транзакций симулятора мерчантов, а его ответ не возвращает PAN и terminalId. Получить ключ запроса можно только из БД, что запрещено условием «данные — только через API».
- *Генерация 10 000 карт (граница ON)* — создаёт 10 000 строк при каждом прогоне. Граница проверена через OFF (10 001 → 400) и минимальное значение 1.
- *Swagger (`/docs`, `*-docs`)* — служебные эндпоинты документации.
- *Нагрузочные свойства* (rate limit под длительной нагрузкой, конкурентный резерв К-6) — модуль нагрузочного тестирования. Здесь проверен только контракт ответа 429.

**Асинхронность.** Появление записи ждём через `Waits.untilDelivered()` — `await().pollDelay(0).pollInterval(250 ms).atMost(10 s)`. Отсутствие проверяем через `Waits.duringAbsenceWindow()` — `during(3 s)`: условие «записи нет» должно держаться всё окно. Разовая проверка прошла бы и при обычной задержке доставки. Границы обоснованы замером: задержка «ответ Gateway → запись видна в поиске» по 10 транзакциям — 180–320 мс, худшая под нагрузкой ~1,8 с. Таймаут 10 с ≈ 5× худшего случая, окно отсутствия 3 с больше худшей задержки и ≈ 10× типичной. Фиксированных пауз (`Thread.sleep`) в коде нет.

## 6. Доказательства работоспособности проверок

| Тест | Внесённое изменение | Результат |
|---|---|---|
| `LoggerRedeliveryApiTest` (мутационная проверка идемпотентности) | В `TransactionService.store` отключена дедупликация (`if (false && existingTransaction.isPresent())`), образ Logger пересобран | 🔴 8 из 23: повтор → `Expected 200 but was 201`, конфликт данных → `Expected 409 but was 201` (запись молча перезаписывалась). После отката мутации — 23/23 🟢 |
| `TransactionJournalApiTest.approvedTransactionReachesJournalAndIsSearchable`, `declinedTransactionIsJournaled` | Через RabbitMQ Management API удалён binding `smp.transactions → transaction-log` (`transaction.log`): Switch одобряет, но сообщения не доходят до Logger | 🔴 `ConditionTimeoutException … didn't complete within 10 seconds`. Тесты отсутствия в том же классе остались 🟢 — так и должно быть. Binding восстановлен |
| `GatewayApiTest.rateLimitRejectsBurstWith429` | Gateway перезапущен с `TRANSACTIONS_RATE_LIMIT_CAPACITY=100000`, `…REFILL_PER_SECOND=100000` | 🔴 `ни один из 400 запросов не получил 429`. Значения 100/100 восстановлены |
| `AuthorizationOutcomeApiTest.nonActiveCardIsDeclined` (нарушение ожидания) | Во входных данных переставлены причины `CARD_BLOCKED` ↔ `CARD_INACTIVE` (код 05 у обеих оставлен) | 🔴 2 из 3: `JSON path declineReason doesn't match. Expected: CARD_INACTIVE Actual: CARD_BLOCKED` — тест различает исходы с одинаковым кодом |
| 30 тестов `known-defect` | Реализация расходится с контрактом (раздел 4) | 🔴 30 из 30 при `-DapiTests.groups=known-defect` |
| `GatewayApiTest.requestIdIsPropagatedThroughProxiedRoute` (при разработке) | Спецификация уже отправляла свой `X-Request-Id`, тест добавлял второй заголовок | 🔴 при первом запуске — тест действительно сверяет значение; исправлен тест |

**Тест, который проходил по неверной причине.** `CardReservationApiTest.reservationEndpointsAreNotPublic[rollback]` в первой версии ожидал 404 на `POST /api/cards/{pan}/rollback` через Gateway и был зелёным. Разбор показал, что 404 отдавал Card Management («резерв со случайным RRN не найден»), а не Gateway. Маршрут открыт, а тест этого не видел. Исправление: тест сначала создаёт настоящий резерв и проверяет принадлежность отказа (`serviceName` ≠ `card-management`) и неизменность баланса. После этого он красный по правильной причине: `Expected 404 but was 200`. Отсюда правило набора: для 4xx проверяется тело и источник отказа, а не только статус.

**Стабильность:** три последовательных прогона `mvn -f services/pom.xml -P api-tests test` на одной и той же БД — 288/288, 288/288, 288/288 (53–74 с каждый).

**Ограничение изоляции.** Кэш карт и бакет rate-limit в Gateway глобальны для стека. Два прогона набора одновременно на одном стеке мешают друг другу: успешный `POST /api/cards` одного прогона сбрасывает кэш, и проверка `X-Cache: HIT` в другом краснеет. Это наблюдалось, когда фоновый прогон наложился на основной. Внутри одного прогона тесты выполняются последовательно и от порядка не зависят. В CI каждый job поднимает свой стек.

## 7. Порядок запуска

Требуется Docker (Compose v2) и JDK 21 + Maven.

```bash
cp .env.example .env                                   # один раз
docker compose up -d --wait --wait-timeout 300         # поднять стек и дождаться healthy
mvn -f services/pom.xml -P api-tests test              # штатный прогон (288 проверок)
```

Или одной командой: `make api-tests`.

Дополнительно:

```bash
# тесты известных дефектов (ожидаемо красные)
mvn -f services/pom.xml -P api-tests test -DapiTests.excludedGroups= -DapiTests.groups=known-defect

# один класс / один тест
mvn -f services/pom.xml -P api-tests test -Dtest=TransactionSearchApiTest
mvn -f services/pom.xml -P api-tests test -Dtest='GatewayApiTest#rateLimitRejectsBurstWith429'

# другой адрес стека
mvn -f services/pom.xml -P api-tests test -Dgateway.url=http://host:8080 -Dlogger.url=http://host:8088
```

Модуль подключается только профилем `api-tests`, поэтому `mvn test` по всему проекту и job'ы `ci.yml` не требуют поднятого стека.

**CI:** `.github/workflows/api-tests.yml` — запуск на push в ветки `practice-5**`, на PR в `main` и вручную (`workflow_dispatch`). Шаги: сборка образов → `docker compose up --wait` → `mvn -P api-tests test` → артефакт `surefire-reports` → логи контейнеров при падении → `docker compose down -v`.
