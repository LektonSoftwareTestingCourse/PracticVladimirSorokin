# A4. Путь платежа

В исходной документации был только успешный путь. Добавлены отказ и откат, исправлен порядок вызовов CMS/BIN. `->>` — синхронный вызов, `-->>` — ответ, `-)` — асинхронная доставка.

## Успешный платёж

```mermaid
sequenceDiagram
    actor T as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as CMS
    participant B as BIN Lookup
    participant D as PostgreSQL
    participant M as Merchant
    participant Q as RabbitMQ
    participant L as Logger

    T->>G: POST /api/transactions<br/>mti=0100
    G->>G: Валидация
    G->>S: POST /api/internal/<br/>route
    S->>S: Нормализация, BIN → issuerId
    S->>A: POST /api/internal/<br/>authorize
    A->>C: GET /api/cards/{pan}
    C-->>A: ACTIVE, срок, баланс, лимиты
    A->>B: GET /api/bin/{bin}
    B-->>A: issuerId для лога
    A->>A: Проверки статуса, срока и баланса
    A->>D: upsert limit_usage<br/>Блок RRN при необходимости
    D-->>A: Лимиты обновлены
    A->>C: POST /api/cards/{pan}/<br/>reserve
    C->>D: Резерв и баланс<br/>Событие outbox
    C-->>A: 200, карта после резерва
    A-->>S: 200, APPROVED/00<br/>rrn, authCode
    S->>M: POST /api/simulator/<br/>merchant/fee
    M-->>S: Комиссия или отсутствие данных
    S-)Q: smp.transactions / transaction.log
    par Ответ клиенту
        S-->>G: 200, APPROVED/00
        G-->>T: 200, APPROVED/00
    and Доставка в журнал
        Q-)L: transaction-log
        L->>D: Сохранить transactions
        L-)T: WebSocket через Dashboard
    end
```

Обновление outbox → Notification идёт независимо, см. [RabbitMQ](05-rabbitmq.md). Запись в журнале может появиться до или после HTTP-ответа: проверяется её появление за время ожидания, а не строгий порядок этих двух событий.

## Отказ: карта BLOCKED

```mermaid
sequenceDiagram
    actor T as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as CMS
    participant B as BIN Lookup
    participant M as Merchant
    participant Q as RabbitMQ
    participant L as Logger

    T->>G: POST /api/transactions
    G->>S: POST /api/internal/<br/>route
    S->>A: POST /api/internal/<br/>authorize
    A->>C: GET /api/cards/{pan}
    C-->>A: status=BLOCKED
    A->>B: GET /api/bin/{bin}
    B-->>A: Ответ справочника
    Note over A,C: limit_usage и reserve не вызываются
    A-->>S: HTTP 403<br/>DECLINED/05<br/>CARD_BLOCKED
    S->>M: Запрос комиссии
    M-->>S: Результат запроса
    S-)Q: Результат DECLINED
    S-->>G: HTTP 200, DECLINED/05
    G-->>T: HTTP 200, DECLINED/05
    Q-)L: Сохранение отказа в журнале
```

HTTP-статус и `responseCode` — разные поля. Authorization возвращает 403, Switch передаёт бизнес-отказ в HTTP 200.

## Откат после ошибки публикации

```mermaid
sequenceDiagram
    actor T as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as CMS
    participant D as PostgreSQL
    participant Q as RabbitMQ

    T->>G: Платёж 0100
    G->>S: route
    S->>A: authorize
    Note over A,D: Проверки, увеличение usage и резерв уже выполнены
    A-->>S: APPROVED/00 с rrn
    S->>Q: convertAndSend
    Q--xS: AmqpException
    S->>A: POST /api/internal/rollback<br/>pan, rrn, amount
    A->>C: POST /api/cards/{pan}/<br/>rollback
    alt Откат успешен
        C->>D: reservation → ROLLED_BACK<br/>баланс += amount, outbox
        C-->>A: 200
        A-->>S: 200, rollback APPROVED
    else Откат не выполнен
        C-->>A: Ошибка
        A-->>S: rollback DECLINED или ошибка связи
        Note over S,C: Ошибка логируется, возврат денег не гарантирован
    end
    S-->>G: 200, DECLINED/96
    G-->>T: 200, DECLINED/96
```

По [ТЗ Switch, п. 3–4](../../../tz/03-switch.md) reversal обозначается `mti=0400` и должен запускаться при отсутствии publisher-confirm за 2 секунды. В коде откат — отдельный HTTP-запрос без MTI. `publisher-confirm-type: correlated` включён, но `LoggerClient` не ждёт подтверждения: откат начинается только при исключении отправки. `AuthServiceImpl.rollback()` вызывает CMS и не уменьшает `limit_usage`. Эти различия учтены в новых кейсах P3-10 и в [состояниях](06-states.md).

Подтверждение: [маршруты Gateway](../../../services/gateway/src/main/resources/application.yml), [RouteController](../../../services/switch/src/main/java/com/processing/controller/RouteController.java), [RouteService](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [AuthorizationClient](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java), [LoggerClient](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [настройки Switch](../../../services/switch/src/main/resources/application.yml), [AuthControllerImpl](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java), [AuthServiceImpl](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [CardServiceImpl](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [TransactionService](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java).
