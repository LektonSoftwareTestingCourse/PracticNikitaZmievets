# Тест-кейсы и анализ пирамиды

## Метод и границы

Всего 88 исходных кейсов практики 2 и 20 новых, то есть 108. Каждая строка с ID считается
одним кейсом, даже если содержит несколько граничных значений. Таблицы классов/границ и
строки PICT повторно не считаются: их проекции уже имеют ID в разделе 4
[практики 2](../practice-2/test-design.md).

Это спецификация будущих проверок, не отчёт о выполнении. Ожидания описывают либо
реализованный контракт, либо явно обозначенное требование/инвариант, которое код может нарушать.

- `unit`: вызов Java-метода, без Spring/HTTP/БД/брокера; внешние зависимости mock/stub.
- `api`: реальный HTTP binding, validation и mapping одного сервиса или HTTP-клиент со stub-сервером; внешние зависимости изолированы. Проверка DB-эффекта в этот уровень не входит.
- `integration`: настоящая БД, брокер, транзакция или несколько выбранных сервисов. Даже если вход HTTP, проверка persistence/очереди относится сюда.
- `e2e`: полный бизнес-путь от внешнего API через реальные сервисы и инфраструктуру. Браузер для backend E2E не обязателен.

Все суммы в копейках. Базовая карта — PAN `4000003458730237`, ACTIVE, expiry 2027-12,
RUB (643), dailyLimit=10000, monthlyLimit=30000, balance=20000, usage=0, если строка
не переопределяет fixture. Для независимых прогонов карта/usage восстанавливаются.
Запросы содержат все обязательные валидные поля [AuthorizationRequest.java](../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java);
STAN/UUID/RRN уникальны в пределах соответствующего сценария. BIN зарегистрирован.
Для SQL-проверок используется PostgreSQL со штатными миграциями, а не H2.

В async-кейсах timeout 10s и интервал polling 100ms — параметры тестового стенда, не SLA продукта.
Корреляция выполняется по id/RRN/STAN/PAN, а не общим количествам чужих записей.
Журнал опрашивается через `GET /api/transactions/search?pan=<PAN>&limit=500`;
нужные RRN/STAN ищутся в полученных строках (при необходимости с пагинацией),
поскольку отдельных query-фильтров rrn/stan нет.
При истечении timeout сохраняются очереди, payload и логи для диагностики.
TTL-тест использует отдельное окно 65s. Разрушительные fixtures и fault injection — только в изолированной тестовой среде.

## Б. Новые тест-кейсы

P3-Rxx — локальные идентификаторы проверяемых требований этой практики;
точная формулировка указана в колонке требования, первоисточник — в последней колонке.
Для N14/N16 нормативный источник — задание модуля 3; ссылки на код показывают место проверки.
N17 проверяет инвариант сохранности лимита при ошибке reserve.

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N01 | P3-R01: BIN routing | unit | позитивный / негативный | RoutingService с таблицей 400000 → ISS001 | Вызвать getIssuerIdByPan для 4000003458730237, 9999990000000000, null и 12345 | ISS001 только для известного BIN; иначе UnknownBinException | Класс эквивалентности; [RoutingService.java](../../services/switch/src/main/java/com/processing/service/RoutingService.java) |
| N02 | P3-R02: отказ до побочных эффектов | unit | негативный | AuthServiceImpl; CMS stub отдаёт BLOCKED; BIN stub empty | Вызвать authorize валидного запроса; проверить вызовы mock | DECLINED/05 CARD_BLOCKED; upsertLimitUsage и reserve не вызваны | Класс состояния; [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [DeclineOutcome.java](../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java) |
| N03 | P3-R03: срок по UTC-дате запроса | unit | граничный | ACTIVE; expiry 2026-10; balance/limits достаточны; mocks успешны | Изолированно authorize с transmissionDateTime 2026-10-31T23:59:59Z и 2026-11-01T00:00:00Z | Первый APPROVED, второй DECLINED/54; у второго нет reserve | Граничное значение; [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java) |
| N04 | P3-R04: ошибка публикации вызывает компенсацию | unit | негативный | RouteService; RoutingService stub → ISS001; AcquiringFeeClient stub; authorize mock APPROVED с RRN; LoggerClient mock false; rollback mock APPROVED | Вызвать route и проверить аргументы rollback | Один rollback с исходными PAN/amount и полученным RRN; итог DECLINED/96 | Сценарий; [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| N05 | P3-R04: declined не компенсируется | unit | негативный | RouteService; RoutingService stub → ISS001; AcquiringFeeClient stub; authorize mock DECLINED CARD_BLOCKED; LoggerClient mock false | Вызвать route; проверить rollback | Возвращён исходный DECLINED/05; rollback не вызван | Класс эквивалентности; [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| N06 | P3-R05: предел ошибок outbox | unit | негативный / граничный | PENDING событие retryCount=0; maxRetryCount=3; RabbitTemplate бросает AmqpException; repository mock возвращает сохранённое | Трижды processSingleEvent, каждый раз передав сохранённое предыдущее состояние | После ошибок 1/2 статус PENDING, после 3 — FAILED; retryCount=3 и lastError сохранён | Граничное значение; [OutboxEventProcessorImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [CardOutboxEventData.java](../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardOutboxEventData.java) |
| N07 | P3-R06: контракт HTTP decline | api | негативный | Реальный AuthControllerImpl; AuthService stub отдаёт CARD_BLOCKED/05 | POST /api/internal/authorize с валидным DTO | HTTP 403 и тело DECLINED, responseCode=05, declineReason=CARD_BLOCKED | Контракт; [AuthControllerImpl.java](../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java) |
| N08 | P3-R07: Gateway validation | api | негативный | Gateway; Switch HTTP stub со счётчиком; остальные поля валидны | POST /api/transactions с pan=4000ABC | HTTP 400; Switch не получил запрос | Класс эквивалентности / контракт; [TransactionRequestValidator.java](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [TransactionValidationFilter.java](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionValidationFilter.java) |
| N09 | P3-R08: BIN graceful degradation | api | негативный | Реальный BinLookupClient направлен на HTTP stub | Ответить HTTP 500 на GET /api/bin/400000; вызвать getIssuerId валидного PAN | Optional.empty; ровно один HTTP-запрос, исключение наружу не выходит | Контракт клиента; [BinLookupClient.java](../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java) |
| N10 | P3-R09: SQL дневного лимита | integration | граничный | PostgreSQL; usage daily=2000 monthly=5000; limits=10000/30000 | На независимых fixtures выполнить upsertLimitUsage с 7999, 8000, 8001 | Затронуто 1/1/0 строк; daily становится 9999/10000/2000, monthly 12999/13000/5000 | Граничное значение / состояние БД; [LimitUsageRepository.java](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java) |
| N11 | P3-R10: атомарность PATCH и outbox | integration | негативный | CMS + PostgreSQL; ACTIVE-карта; scheduler отключён; outbox save контролируемо бросает исключение | PATCH status=BLOCKED; дождаться завершения запроса; читать БД в новой транзакции | PATCH неуспешен; карта остаётся ACTIVE; outbox событие не сохранено — обе записи атомарны | Состояние БД; [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [CardEventNotifierImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java), [TransactionRunnerImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunnerImpl.java) |
| N12 | P3-R11: доставка карточного события | integration | позитивный / async | CMS worker + RabbitMQ + Notification + PostgreSQL; пустая очередь; scheduler управляем; создана карта с уникальным PAN | PATCH status=BLOCKED; запустить worker; polling outbox и notification по PAN в payload | В пределах 10s outbox PROCESSED и notification с routingKey=card.CardServicePatchEvent, eventType=CardServicePatchEvent и нужным PAN | Сценарий / состояние БД; [OutboxEventProcessorImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [CardEventListener.java](../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |
| N13 | P3-R12: доставка и повтор сообщения Logger | integration | позитивный / async | Реальные RabbitMQ, Logger, PostgreSQL; объявлена полная transaction топология; новый UUID | Опубликовать валидный TransactionRequest, дождаться строки; повторить идентичное сообщение с тем же id | В пределах 10s строка доступна и совпадает с сообщением; после повторной обработки всё ещё одна строка; очередь обработана | Контракт / состояние БД; [TransactionLogListener.java](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [TransactionService.java](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
| N14 | P3-R13: consumer retry 3 и DLQ по заданию | integration | негативный / async | Изолированный RabbitMQ и Notification; полная топология; счётчик входов listener; DLQ очищена только в тестовом vhost | Опубликовать некорректный JSON; считать попытки; polling DLQ до 10s, снять сообщение до TTL | По требованию: 3 попытки всего, затем одно сообщение card-notifications-dlq, основная очередь освобождена. Иное число или requeue без конца — несоответствие заданию | Контракт задания; [RabbitMQConfig.java](../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java), [CardEventListener.java](../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |
| N15 | P3-R14: TTL диагностического сообщения | integration | граничный / async | RabbitMQ, обе DLQ объявлены; TTL 60000; consumers DLQ отсутствуют; уникальные marker id | Поместить по одному сообщению через соответствующий DLX; убедиться до 5s в наличии, не извлекая; после 65s проверить через basic.get | До истечения TTL сообщения доступны в DLQ; после срока basic.get пуст; в основных очередях marker не появляется | Граничное значение / контракт; [RabbitMQConfig.java](../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java), [RabbitMQConfig.java](../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java) |
| N16 | P3-R15: confirm 2s и reversal по заданию | integration | негативный / async | Реальный Switch LoggerClient и RabbitMQ; AMQP proxy удерживает только publisher ACK/NACK, публикация доставлена; Authorization и fee — HTTP stubs; authorize APPROVED | Отправить route; удерживать confirm более 2s, но не разрывать socket; записать время и HTTP-запрос компенсации | По заданию: после 2s ожидания (допуск измерения 500ms) отказ 96 и одна компенсация с семантикой 0400. Отдельно проверить MTI; фактический /rollback не содержит его. Код предсказывает отсутствие ожидания и отката | Контракт задания / сценарий; [LoggerClient.java](../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [AuthorizationClient.java](../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) |
| N17 | P3-R16: ошибка reserve не расходует лимит | integration | негативный | Authorization + PostgreSQL; CMS HTTP stub GET=валидная ACTIVE карта, reserve=503 без побочного эффекта; BIN stub; usage=2000/5000 | POST authorize amount=100; дождаться ответа; SELECT usage из новой транзакции | По денежному инварианту: DECLINED/96 SERVICE_UNAVAILABLE, usage остаётся 2000/5000. Статический анализ предсказывает 2100/5100: кандидат на дефект | Сценарий / состояние БД; [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [LimitUsageRepository.java](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java) |
| N18 | P3-R17: откат денег и повтор rollback | integration | позитивный / негативный | CMS + PostgreSQL; ACTIVE, balance=20000; новый RRN; amount=100 | reserve; rollback с тем же PAN/RRN/amount; повторить rollback; проверить БД | После reserve balance=19900, после rollback=20000 и reservation ROLLED_BACK; повтор отклонён, balance=20000, второй возврат не создан | Состояние БД; [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [Reservation.java](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java), [CardController.java](../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java) |
| N19 | P3-R18: успешная транзакция и eventual consistency | e2e | позитивный | Полный контур Gateway/Switch/Auth/CMS/BIN/Merchant/RabbitMQ/Logger/PostgreSQL; ACTIVE-карта, balance=20000, usage=0, limits достаточны; новый STAN | POST /api/transactions amount=100; по полученному RRN polling журнала; прочитать карту и usage | HTTP 200 APPROVED/00; balance=19900, usage=100; в пределах 10s одна APPROVED запись журнала с теми же RRN/amount/PAN; немедленное наличие записи не требуется | Сквозной сценарий; [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java), [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [TransactionService.java](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
| N20 | P3-R19: блокировка карты видна процессингу | e2e | негативный | Полный контур как N19; новая ACTIVE-карта; записаны исходные balance/usage | PATCH карты в BLOCKED через Gateway; POST транзакции; polling Logger по уникальному STAN | PATCH успешен; транзакция HTTP 200 DECLINED/05 CARD_BLOCKED; balance/usage прежние; в пределах 10s запись DECLINED в журнале | Состояние / сквозной сценарий; [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) |

### Почему выбран этот уровень

N01–N06 проверяют ветвления, аргументы и счётчики без инфраструктуры.
N07–N09 проверяют HTTP-преобразование и поведение клиента, поэтому одного unit недостаточно.
N10–N18 требуют SQL, транзакций, RabbitMQ или transport fault injection; mock этой границы
убрал бы сам проверяемый риск. N19/N20 оставлены сквозными: результат связывает изменение
карты, процессинг и последующее появление журнала. Проверка N16 отдельно различает
таймаут confirm, сетевое исключение отправки и формат reversal; N04 не заменяет её.
N13 проверяет идемпотентность по одному message id, а не повтор исходной покупки с новым id.

### Покрытие карты системы

| Область / связь | Новые кейсы |
|---|---|
| А1/А2: внешний вход и Gateway | N08, N19, N20 |
| А2/А3: BIN, HTTP-клиенты, ветвления Authorization | N01, N02, N03, N07, N09 |
| А3: SQL лимитов, CMS, транзакции | N10, N11, N17, N18 |
| А4: отказ и компенсация | N04, N05, N16, N18, N20 |
| А5: обе очереди, повтор, DLQ/TTL, outbox | N06, N12, N13, N14, N15, N16 |
| А6: BLOCKED, EXPIRED, RESERVED → ROLLED_BACK | N02, N03, N18, N20 |

## В. Распределение существующих кейсов

Уровень выбирается для полного содержания строки, а не по названию операции.
Для API явно задана изоляция: фраза «изменений нет» означает отсутствие вызовов изменяющих
зависимостей; если нужно доказать состояние настоящей БД, используется integration.
Шаги HTTP старых кейсов сохраняются, поэтому они не объявлены unit только из-за наличия
внутри простой логики. Выделенные новые unit проверяют дополнительные узкие ветви.
Дубликаты сценариев PICT с разными ID сохранены для полной прослеживаемости.

<!-- TODO: заполнить «Что проверяет тест», «Уровень», «Обоснование» для всех 88 строк.
     «Название» = «Предусловие → Ожидаемый результат» из практики 2 без изменений. -->

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| TC-AUTH-PW-01 | INACTIVE, срок `1026`; Bin Lookup недоступен → `DECLINED/05`, CARD_INACTIVE |  |  |  |
| TC-AUTH-PW-02 | ACTIVE, срок `1029`; Б = A, Д = A, М = A → `APPROVED/00`, баланс уменьшен на A |  |  |  |
| TC-AUTH-PW-03 | EXPIRED, срок `0926` → `DECLINED/54` |  |  |  |
| TC-AUTH-PW-04 | ACTIVE, срок `1026`; Б = A − 1, Д = A, М = A; Bin Lookup недоступен → `DECLINED/51`, INSUFFICIENT_FUNDS |  |  |  |
| TC-AUTH-PW-05 | ACTIVE, срок `1029`; Б > A, Д = A − 1, М > A; Bin Lookup недоступен → `DECLINED/61`, EXCEEDS_AMOUNT_LIMIT |  |  |  |
| TC-AUTH-PW-06 | ACTIVE, срок `1029`; Б > A, Д = A, М = A − 1 → `DECLINED/61`, EXCEEDS_AMOUNT_LIMIT |  |  |  |
| TC-AUTH-PW-07 | BLOCKED, срок `1026` → `DECLINED/05`, CARD_BLOCKED |  |  |  |
| TC-AUTH-PW-08 | ACTIVE, срок `1029`; Б = A − 1, Д > A, М = A → `DECLINED/51`, INSUFFICIENT_FUNDS |  |  |  |
| TC-AUTH-PW-09 | ACTIVE, срок `1026`; Б = A, Д = A, М > A; Bin Lookup недоступен → `APPROVED/00`, баланс уменьшен на A |  |  |  |
| TC-AUTH-PW-10 | EXPIRED, срок `0926`; Bin Lookup недоступен → `DECLINED/54` |  |  |  |
| TC-AUTH-PW-11 | INACTIVE, срок `1029` → `DECLINED/05`, CARD_INACTIVE |  |  |  |
| TC-AUTH-PW-12 | ACTIVE, срок `1026`; Б > A, Д > A, М = A − 1; Bin Lookup недоступен → `DECLINED/61`, EXCEEDS_AMOUNT_LIMIT |  |  |  |
| TC-AUTH-PW-13 | BLOCKED, срок `1029`; Bin Lookup недоступен → `DECLINED/05`, CARD_BLOCKED |  |  |  |
| TC-AUTH-PW-14 | ACTIVE, срок `1026`; Б > A, Д = A − 1, М > A → `DECLINED/61`, EXCEEDS_AMOUNT_LIMIT |  |  |  |
| TC-AUTH-PW-15 | ACTIVE, срок `1026`; Б = A, Д > A, М > A; Bin Lookup недоступен → `APPROVED/00`, баланс уменьшен на A |  |  |  |
| TC-AUTH-PW-16 | EXPIRED, срок `1026` → `DECLINED/54` |  |  |  |
| TC-AUTH-PW-17 | ACTIVE, срок `0926` → `DECLINED/54` |  |  |  |
| TC-AUTH-PW-18 | ACTIVE, срок `1029`; Б = A − 1, Д = A, М > A → `DECLINED/51`, INSUFFICIENT_FUNDS |  |  |  |
| TC-AUTH-PW-19 | BLOCKED, срок `1026` → `DECLINED/05`, CARD_BLOCKED |  |  |  |
| TC-AUTH-PW-20 | INACTIVE, срок `1026`; Bin Lookup недоступен → `DECLINED/05`, CARD_INACTIVE |  |  |  |
| TC-AUTH-PW-21 | ACTIVE, срок `1029`; Б > A, Д = A, М = A − 1 → `DECLINED/61`, EXCEEDS_AMOUNT_LIMIT |  |  |  |
| TC-AUTH-PW-22 | EXPIRED, срок `1029`; Bin Lookup недоступен → `DECLINED/54` |  |  |  |
| TC-AUTH-PW-23 | ACTIVE, срок `1026`; Б > A, Д = A, М = A → `APPROVED/00`, баланс уменьшен на A |  |  |  |
| TC-AUTH-PW-24 | ACTIVE, срок `1026`; Б > A, Д = A − 1, М > A → `DECLINED/61`, EXCEEDS_AMOUNT_LIMIT |  |  |  |
| TC-CMS-PW-01 | — → 201; ISS001; 643; лимиты 5 000 000 / 300 000 000; баланс 0 |  |  |  |
| TC-CMS-PW-02 | — → 201; ISS005; 840; лимиты 0 / 0; баланс 100 000 000 |  |  |  |
| TC-CMS-PW-03 | — → 201; ISS005; 643; лимиты 15 000 000 / 450 000 000; баланс 50 000 000 |  |  |  |
| TC-CMS-PW-04 | — → 201; ISS001; 643; лимиты 5 000 000 / 5 000 000; баланс 50 000 000 |  |  |  |
| TC-CMS-PW-05 | — → 201; ISS001; 643; лимиты 15 000 000 / 450 000 000; баланс 100 000 000 |  |  |  |
| TC-CMS-PW-06 | — → 201; ISS005; 643; лимиты 0 / 300 000 000; баланс 0 |  |  |  |
| TC-CMS-PW-07 | — → 201; ISS001; 840; лимиты 0 / 300 000 000; баланс 50 000 000 |  |  |  |
| TC-CMS-PW-08 | — → 201; ISS005; 840; лимиты 15 000 000 / 15 000 000; баланс 0 |  |  |  |
| TC-CMS-PW-09 | — → 201; ISS005; 840; лимиты 5 000 000 / 150 000 000; баланс 100 000 000 |  |  |  |
| TC-CMS-PW-10 | — → 201; ISS005; 643; лимиты 0 / 0; баланс 50 000 000 |  |  |  |
| TC-CMS-PW-11 | — → 201; ISS005; 643; лимиты 15 000 000 / 300 000 000; баланс 100 000 000 |  |  |  |
| TC-CMS-PW-12 | — → 201; ISS001; 643; лимиты 5 000 000 / 150 000 000; баланс 0 |  |  |  |
| TC-CMS-PW-13 | Запомнить `total` → 4xx: BIN вне справочника; карта не создана |  |  |  |
| TC-CMS-PW-14 | Запомнить `total` → 400: имя не латиницей; карта не создана |  |  |  |
| TC-CMS-PW-15 | Запомнить `total` → 400: monthlyLimit < dailyLimit; карта не создана |  |  |  |
| TC-CMS-PW-16 | Запомнить `total` → 400: currencyCode не из 3 цифр; карта не создана |  |  |  |
| TC-CMS-PW-17 | Запомнить `total` → 4xx: BIN вне справочника; карта не создана |  |  |  |
| TC-CMS-PW-18 | Запомнить `total` → 400: имя не латиницей; карта не создана |  |  |  |
| TC-CMS-PW-19 | Запомнить `total` → 400: monthlyLimit < dailyLimit; карта не создана |  |  |  |
| TC-CMS-PW-20 | Запомнить `total` → 400: currencyCode не из 3 цифр; карта не создана |  |  |  |
| TC-CMS-PW-21 | Запомнить `total` → 4xx: BIN вне справочника; карта не создана |  |  |  |
| TC-CMS-PW-22 | Запомнить `total` → 400: имя не латиницей; карта не создана |  |  |  |
| TC-CMS-PW-23 | Запомнить `total` → 400: currencyCode не из 3 цифр; карта не создана |  |  |  |
| TC-CMS-PW-24 | Запомнить `total` → 400: monthlyLimit < dailyLimit; карта не создана |  |  |  |
| TC-AUTH-EP-01 | Карты `4000000000000000` нет → `DECLINED/14` |  |  |  |
| TC-AUTH-EP-02 | ACTIVE-карта удалена через DELETE → `DECLINED/14`: удалённая карта не участвует |  |  |  |
| TC-AUTH-EP-03 | ACTIVE-карта, Б, Д, М > A → Каждый запрос → 400; баланс не изменился |  |  |  |
| TC-AUTH-EP-04 | ACTIVE-карта, Б, Д, М > 2A → Оба `APPROVED/00`; Б уменьшен на 2A |  |  |  |
| TC-AUTH-EP-05 | ACTIVE-карта; `card-management` остановлен → `DECLINED/05`, `ISSUER_TIMEOUT` |  |  |  |
| TC-AUTH-BV-01 | Использований нет, Б = 100 000 000 → Первые два `APPROVED`; 15 000 001 → 61 |  |  |  |
| TC-AUTH-BV-02 | За день одобрено 10 000 000 → Первые два `APPROVED`; 5 000 001 → 61 |  |  |  |
| TC-AUTH-BV-03 | За прошлые дни месяца 290 000 000, за день 0 → Первые два `APPROVED`; 10 000 001 → 61 |  |  |  |
| TC-AUTH-BV-04 | Б = 5 000 000, лимиты достаточны → Первые два `APPROVED` (Б = 1 / 0); 5 000 001 → 51 |  |  |  |
| TC-AUTH-BV-05 | ACTIVE-карта, Б, Д, М > A → 0 → 400; 1 и 2 → `APPROVED` |  |  |  |
| TC-AUTH-BV-06 | Три карты со сроком `0926`, `1026`, `1126` → `0926` → 54; `1026` и `1126` → `APPROVED` |  |  |  |
| TC-AUTH-BV-07 | Срок `1026`; время транзакции управляемо → Первые два `APPROVED`; 01.11 → 54 |  |  |  |
| TC-AUTH-BV-08 | ACTIVE-карта, Б, Д, М > A → 15 и 17 → 400 `VALIDATION_ERROR`; 16 → `APPROVED` |  |  |  |
| TC-AUTH-K-01 | ACTIVE, `1029`; Б = A − 1, Д = A − 1 → `DECLINED/61` (не 51) |  |  |  |
| TC-AUTH-K-02 | ACTIVE, `1029`; Б = A − 1, М = A − 1 → `DECLINED/61` (не 51) |  |  |  |
| TC-AUTH-K-03 | BLOCKED, срок `0926` → `CARD_BLOCKED`, 05 (не 54) |  |  |  |
| TC-AUTH-K-04 | ACTIVE, `1026`; Б = Д = М = A → `APPROVED/00`; Б = 0; за день использовано 15 000 000, за месяц 300 000 000 |  |  |  |
| TC-AUTH-K-05 | Выполнен TC-AUTH-K-04 → `DECLINED/61`; Б = 0 |  |  |  |
| TC-AUTH-K-06 | ACTIVE, Б = A, Д и М > A → Один `APPROVED`, второй `DECLINED/51`; Б = 0; usage +A один раз |  |  |  |
| TC-AUTH-K-07 | ACTIVE, Б, Д, М > A; stub CMS: GET успешен, `/reserve` → 503 → `DECLINED/05`; Б и `limit_usage` не изменены |  |  |  |
| TC-CMS-EP-01 | Запомнить `total` → Оба 400; `total` не изменился |  |  |  |
| TC-CMS-EP-02 | Есть BLOCKED-карта → 200 со всеми полями Card; 404; каждый запрос с `4000ABC` → 400 |  |  |  |
| TC-CMS-EP-03 | Сгенерировано 100 карт → Только BLOCKED, `total` равен их числу; 400 |  |  |  |
| TC-CMS-EP-04 | В БД > 50 карт → 10 карт; 50 карт; `total` одинаков |  |  |  |
| TC-CMS-EP-05 | Запомнить `total` → 201: по 20 на BIN, ACTIVE 95, INACTIVE 3, BLOCKED 2, PAN по Луну; второй → 400 |  |  |  |
| TC-CMS-EP-06 | ACTIVE-карта с известными полями → 200, изменён только статус; 400; 400; остальные поля прежние |  |  |  |
| TC-CMS-EP-07 | ACTIVE-карта BIN 400003 → 2xx; 404; карты нет в списке; reserve → 404; 404 |  |  |  |
| TC-CMS-BV-01 | Запомнить `total` → 6 → 201; 5 и 7 → 400 |  |  |  |
| TC-CMS-BV-02 | — → 0 и 256 → 400; остальные → 201, имя сохранено полностью |  |  |  |
| TC-CMS-BV-03 | — → `643` → 201; остальные → 400 |  |  |  |
| TC-CMS-BV-04 | — → −1 → 400; 0 и 1 → 201 |  |  |  |
| TC-CMS-BV-05 | — → 4 999 999 → 400; остальные → 201 |  |  |  |
| TC-CMS-BV-06 | Запомнить `total` → 0 и 10 001 → 400; остальные → ровно `count` карт |  |  |  |
| TC-CMS-BV-07 | В БД ≥ 2 карт → 0 → 400; 1 и 2 карты |  |  |  |
| TC-CMS-BV-08 | В БД ≥ 2 карт → −1 → 400; 0 — первая карта; 1 — вторая |  |  |  |
| TC-CMS-BV-09 | Карты ACTIVE, Б = 1 000 000 → 0 → 400; 1 000 001 → отказ, Б = 1 000 000; остальные → 200, Б уменьшен на `amount` |  |  |  |
| TC-CMS-BV-10 | — → Баланс в [1 000 000; 50 000 000]; `dailyLimit` в [5 000 000; 30 000 000]; `monthlyLimit` = дневной × 30 |  |  |  |
| TC-CMS-K-01 | Запомнить `total` → 400; карта не создана |  |  |  |
| TC-CMS-K-02 | — → Обе 201; PAN различаются и проходят проверку Луна |  |  |  |
| TC-CMS-K-03 | ACTIVE-карта, Б = 1 000 000 → Первый 200; повтор не списывает (2xx с тем же результатом или 409); Б = 850 000 |  |  |  |

### Уточнения старых ожиданий перед реализацией

Исходный файл не переписывается. Распределение не означает подтверждения его oracle.

<!-- TODO: указать ID практики 2 (TC-…), к которым относится каждый пункт. -->

- [ID]: при недоступном CMS код возвращает SERVICE_UNAVAILABLE/96, а не ISSUER_TIMEOUT/05.
- [ID]: при ошибке GET карты выполнение заканчивается до BIN lookup. Stub BIN в этих строках может вообще не вызываться; они не доказывают fallback.
- [ID]: baseline balance=20000 маскирует monthly-границу 25000; для этой проверки нужно balance ≥ 25001 и достаточный dailyLimit.
- [ID]: при одновременной нехватке баланса и превышении лимита фактический приоритет balance даёт 51; ожидание 61 требует согласования с требованием.
- [ID]: неизменность usage после ошибки reserve требует реального commit и может не выполняться (N17).
- [ID]: сравнение срока основано на transmissionDateTime UTC, поэтому фиксации только системных часов недостаточно.
- [ID]: default HTTP limit=10 вместо 50; fixture с 60 совпадающими картами различит 10 и 50.
- [ID]: идемпотентность денежного эффекта не означает два HTTP 200 — повтор RRN отклоняется.
- [ID]: ранняя валидация может завершить кейс до проверки PAN/status/BIN; покрытие PICT-пар не гарантирует выполнение всех ветвей.

## Г. Пропорции

<!-- TODO: заполнить столбец «Старые» после части В; Итого = Старые + Новые; Доля = Итого / 108 × 100%. -->

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit |  | 6 |  |  |
| api / integration |  | 12 |  |  |
| — в т.ч. api |  | 3 |  |  |
| — в т.ч. integration |  | 9 |  |  |
| e2e |  | 2 |  |  |
| **Итого** | **88** | **20** | **108** | **100%** |

### Объяснение распределения

<!-- TODO: сверить формулировки с итоговыми числами после заполнения части В. -->

Преобладание среднего слоя связано с исходным набором: практика 2 описана как HTTP-проверки
Authorization/CMS, включая сохранение карт, фильтры, резервы и SQL-лимиты. Перенос этих
кейсов в unit без изменения шагов и oracle исказил бы содержание. Новые проверки также
закрывают риски очередей, outbox, компенсации и commit — их нельзя доказать mock-объектами.
Поэтому пропорции сознательно не подгоняются под 70/20/10.

Перекоса в E2E нет: сквозными остаются только [перечислить e2e-кейсы].
Остальные сбои воспроизводятся на более узком уровне.
Дефицита контрактных проверок по количеству нет, но есть **дефицит unit-проверок**:
[N] из 108 — небольшой фундамент, поэтому набор ближе к «ромбу», чем к классической пирамиде.
Это ограничение текущего дизайна, а не признак автоматически хорошего покрытия.

В следующем модуле полезно разложить повторяющиеся отказы Authorization на unit логики и небольшой
параметризованный API-набор; отдельно расширить unit для нормализации, доменных денежных
правил и генераторов. Здесь исходные 88 ID не удалены и не пересчитаны задним числом.
Количество не измеряет качество: PICT-строки часто отсекаются ранней валидацией, а большая
доля API не заменяет проверки транзакций и потери сообщений. Времена выполнения и
стабильность этой пирамиды ещё не измерены, поскольку это этап тест-дизайна.
