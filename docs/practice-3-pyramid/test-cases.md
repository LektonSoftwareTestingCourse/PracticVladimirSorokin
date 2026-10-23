# Практика 3. Карта системы и пирамида тестирования

Схемы: [контекст](architecture/01-context.md), [сервисы](architecture/02-containers.md), [компоненты](architecture/03-components.md), [платёж](architecture/04-sequences.md), [RabbitMQ](architecture/05-rabbitmq.md), [состояния](architecture/06-states.md).

## 1. Новые тест-кейсы

Статус кейсов — **NOT RUN**. Суммы указаны в копейках. Для каждого финансового кейса используется отдельная карта P: ACTIVE, будущий срок, BIN 400000, валюта 643, баланс 1000, дневной лимит 1000, месячный 2000, usage за текущий день и месяц равен 0. BIN зарегистрирован; симуляторы не создают фоновые платежи.

Базовый запрос платежа: `mti=0100`, `stan=730001` (отдельный для каждого кейса), `pan=P`, `processingCode=000000`, `amount=100`, `currencyCode=643`, `transmissionDateTime` — текущее время UTC, `terminalId=TERM0001`, `terminalType=POS`, `merchantId=MERCH0000000001`, `mcc=5411`, `acquirerId=ACQ001`, `issuerId=ISS001`. Поля строковые, кроме amount.

Для async-проверок — опрос каждые 100 мс до 10 секунд. Это время ожидания теста; отдельный предел publisher-confirm из ТЗ — 2 секунды. Сообщения сопоставляются по UUID, а события резерва — по PAN и RRN.

### Unit: логика в памяти

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-01 | [ТЗ Switch, п. 2: BIN → issuerId](../../tz/03-switch.md); [RoutingService](../../services/switch/src/main/java/com/processing/service/RoutingService.java) | unit | + / − | RoutingService с таблицей 400000 → ISS001, 400001 → ISS002; HTTP не запускается | Вызвать getIssuerIdByPan для PAN с BIN 400000, 400001 и 999999; отдельно для null и строки из 5 цифр | ISS001; ISS002; в остальных вариантах UnknownBinException | КЭ: известный, неизвестный, неполный BIN |
| P3-02 | [ТЗ Gateway: валидация суммы](../../tz/02-gateway.md); [TransactionRequestValidator](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java) | unit | + / − | Валидатор без Spring; базовый запрос | По отдельности вызвать validate с amount=-1, 0 и 1; остальные поля не менять | Для -1 и 0 — TransactionValidationException, для 1 — ошибок нет | ГЗ: минимальная положительная сумма |
| P3-03 | [Reservation.startRollback](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java): запрет повторного отката | unit | − | Резерв P на 100, RRN 012345678901, статус RESERVED | Создать rollback на 100; вызвать rolledBack; у полученного резерва снова вызвать startRollback(100) | Статус ROLLED_BACK; повторный вызов бросает RollbackAlreadySatisfiedException | Сценарий повторного перехода состояния |
| P3-04 | [OutboxEventProcessorImpl](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java): три ошибки → FAILED | unit | − | Событие PENDING, retryCount=0; maxRetryCount=3; mock RabbitTemplate бросает AmqpException; mock OutboxRepository сохраняет переданное состояние | Трижды вызвать processSingleEvent, каждый раз передавая состояние, сохранённое предыдущим вызовом | После попыток: PENDING/1, PENDING/2, FAILED/3; lastError заполнен; отправка вызвана 3 раза | ГЗ: число попыток 2 / 3; состояние outbox |

### API: HTTP-контракт одного сервиса

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-05 | [TransactionValidationFilter](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionValidationFilter.java): Gateway принимает только 0100 | api | − | Gateway запущен; вместо Switch — HTTP-заглушка со счётчиком запросов | POST /api/transactions по базовому шаблону, изменить только mti на 0400; проверить вызовы заглушки | HTTP 400, error=VALIDATION_ERROR; запрос до Switch не дошёл | КЭ: неподдерживаемый MTI; контракт |
| P3-06 | [AuthControllerImpl](../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java): CARD_BLOCKED → HTTP 403 | api | − | HTTP-слой Authorization; AuthServiceImpl заменён двойником, возвращающим DECLINED/05 с причиной CARD_BLOCKED | POST /api/internal/authorize с валидным базовым запросом | HTTP 403; status=DECLINED, responseCode=05, declineReason=CARD_BLOCKED; rrn и authCode пусты | Контракт: соответствие бизнес-отказа HTTP-ответу |
| P3-07 | [CardController.rollback](../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java): повторный rollback → 409 | api | − | CMS со своей БД; карта P; создан резерв на 100 с RRN 012345678901, баланс 900 | Дважды POST /api/cards/{P}/rollback с телом {"pan":"P","amount":100,"rrn":"012345678901"}, подставив PAN карты; после каждого запроса GET карты напрямую в CMS | Первый ответ 200 и баланс 1000; второй 409, баланс остаётся 1000 | Контракт; повторный запрос |

### Integration: SQL и сообщения

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-08 | [TransactionLogListener](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java): событие → журнал | integration | + | Настоящие Logger, RabbitMQ и PostgreSQL; топология объявлена; записи с UUID U ещё нет | Опубликовать TransactionRequest с id=U, pan=P, stan=730008, amount=100, status=APPROVED в smp.transactions с ключом transaction.log, используя конвертер Switch; опрашивать transactions по U | За время ожидания появляется одна строка с U; PAN, STAN, сумма и статус совпадают с сообщением | Сценарий доставки; состояние БД |
| P3-09 | [OutboxProcessor](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java) → [CardEventListener](../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) | integration | + | Настоящие CMS, Notification, RabbitMQ и PostgreSQL; карта P, RRN 012345678909 новый | Выполнить reserve на 100; найти outbox-событие по P/RRN; опрашивать outbox_event и card_notifications | Баланс 900; outbox-событие становится PROCESSED; в Notification появляется CardServiceReserveEvent с теми же PAN, RRN и amount=100 | Сценарий outbox → Notification; eventual consistency |
| P3-10 | [ТЗ Switch, п. 3–4](../../tz/03-switch.md) и [ТЗ Authorization, Reversal](../../tz/04-authorization.md): таймаут confirm и восстановление сумм | integration | − | Switch, Authorization, CMS, RabbitMQ, PostgreSQL; карта P. AMQP-прокси пропускает публикацию, но задерживает подтверждение на 5 секунд; запись в Logger не считается подтверждением публикации | POST /api/internal/route, amount=100; проверить время ожидания confirm, откат резерва, баланс и limit_usage | После предела ожидания confirm 2 с запускается rollback; клиент получает DECLINED/96; резерв ROLLED_BACK, баланс 1000, usage возвращён к 0 | Сценарий отказа на асинхронной связи; состояние БД |
| P3-11 | [LimitUsageRepository.upsertLimitUsage](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java): атомарная проверка лимитов | integration | + / − | PostgreSQL; строк для P нет; dailyLimit=150, monthlyLimit=1000; один день UTC | Одновременно в двух независимых транзакциях вызвать upsertLimitUsage(P, день, 100, 150, 1000); дождаться обоих commit; прочитать строку | Один вызов возвращает 1, другой 0; одна строка, daily_amount=100, monthly_amount=100 | ГЗ лимита; конкурентный доступ; состояние БД |
| P3-12 | [RabbitMQConfig Switch](../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java): не более трёх доставок → DLQ | integration | − | Logger, RabbitMQ и PostgreSQL в изолированном стенде; исходные имена transaction-log и transaction-log-dlq, чужих сообщений и потребителей нет; Switch объявил DLX/DLQ; сохранён корректный запрос с id=U, stan=730012, amount=100 | Скопировать запрос U, изменить только amount на 101; через конвертер Switch опубликовать в smp.transactions / transaction.log; считать обработки по уникальному STAN 730012 в журнале listener; опрашивать transaction-log-dlq | Не более 3 доставок; сообщение в DLQ, повторная доставка прекращена; строка U не изменилась | Сценарий ошибки обработки; повторная доставка |

### E2E: критические пути

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-13 | [ТЗ Authorization, п. 2–4](../../tz/04-authorization.md), [путь платежа](architecture/04-sequences.md): одобрение и журнал | e2e | + | Все сервисы, PostgreSQL и RabbitMQ работают; карта P; Dashboard открыт и подключён к WebSocket до платежа | POST через Gateway /api/transactions, amount=100, stan=730013; получить карту напрямую в CMS; проверить usage, запись Logger и строку Dashboard по RRN | HTTP 200, APPROVED/00; RRN — 12 цифр, authCode — 6 символов A–Z/0–9; баланс 900, usage=100/100; в Logger и Dashboard появляется тот же платёж | Сквозной сценарий успешного платежа |
| P3-14 | [ТЗ Authorization: BLOCKED](../../tz/04-authorization.md), [переход карты](architecture/06-states.md) | e2e | − | Весь стенд; отдельная карта P; исходные баланс 1000 и usage=0 | PATCH через Gateway /api/cards/{P}, status=BLOCKED; POST через Gateway /api/transactions, amount=100, stan=730014; проверить карту, usage, резервы и Logger | PATCH 200; платёж HTTP 200, DECLINED/05, CARD_BLOCKED; баланс 1000, usage=0, резерва нет; в Logger отказ с теми же PAN и STAN | Состояние ACTIVE → BLOCKED; сквозной отказ |

P3-10 и P3-12 проверяют требования, с которыми есть расхождения в коде: нет ожидания confirm и восстановления usage при rollback; `x-max-delivery` не задаёт работающий предел повторной доставки. Подробности и исходники — в [A4](architecture/04-sequences.md) и [A5](architecture/05-rabbitmq.md).

## 2. Распределение кейсов практики 2

Исходные данные и шаги — в [test-design.md](../practice-2/test-design.md). Общие шаги A требуют чтения usage и проверки резерва, общие шаги C — сверки данных через GET **и БД**. Поэтому все 119 старых кейсов в их текущем виде отнесены к integration; ID сохранены.

Для новых unit внешние зависимости подменяются; API проверяет HTTP-статус и тело одного сервиса; integration — реальные SQL/AMQP; E2E — полный платёж через Gateway.

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| A-01 | Успешная авторизация | Одобрение 00, резерв и обе использованные суммы | integration | Сверка баланса, резерва и limit_usage |
| A-02 | Карта отсутствует | Отказ 14 без создания usage и резерва | integration | Проверка отсутствия записей в БД |
| A-03 | Карта INACTIVE | CARD_INACTIVE без финансовых изменений | integration | Чтение limit_usage после запроса |
| A-04 | Карта BLOCKED | CARD_BLOCKED без финансовых изменений | integration | Чтение limit_usage после запроса |
| A-05 | Статус EXPIRED при будущем сроке | Отказ 54 именно по статусу | integration | Контроль неизменности usage и резервов |
| A-06 | CMS недоступен | Отказ и сохранность денег при сбое зависимости | integration | Связь Authorization → CMS и состояние БД |
| A-07 | BIN Lookup недоступен | Одобрение с issuerId от Switch | integration | Реальный отказ HTTP-зависимости, проверка резерва и usage |
| A-08 | Три финансовых равенства | Одновременное достижение лимитов и нулевого остатка | integration | Резерв, баланс и limit_usage проверяются вместе |
| A-10 | RRN и authCode на 30 платежах | Уникальность RRN в выборке, формат и учёт сумм | integration | Генерация RRN связана с rrn_seq; проверяются usage и баланс |
| A-BB-01 | Баланс: 99 при остатке 100 | B=100; 00, B=1 | integration | Сверка резерва, баланса и usage |
| A-BB-02 | Баланс: 100 при остатке 100 | B=100; 00, B=0 | integration | Сверка резерва, баланса и usage |
| A-BB-03 | Баланс: 101 при остатке 100 | B=100; 51, B=100 | integration | Сверка резерва, баланса и usage |
| A-BD-01 | Дневной лимит: 99 при остатке 100 | D=300, dailyUsed=200; 00, dailyUsed=299 | integration | Чтение и обновление limit_usage |
| A-BD-02 | Дневной лимит: 100 при остатке 100 | D=300, dailyUsed=200; 00, dailyUsed=300 | integration | Чтение и обновление limit_usage |
| A-BD-03 | Дневной лимит: 101 при остатке 100 | D=300, dailyUsed=200; 61, суммы прежние | integration | Чтение и обновление limit_usage |
| A-BE-01 | Прошлый месяц срока | 54 без изменения сумм | integration | Проверка срока вместе с финансовым состоянием в БД |
| A-BE-02 | Текущий месяц срока | 00 с резервом и расходом usage | integration | Проверка срока вместе с финансовым состоянием в БД |
| A-BE-03 | Будущий месяц срока | 00 с резервом и расходом usage | integration | Проверка срока вместе с финансовым состоянием в БД |
| A-BE-04 | Предпоследняя секунда месяца | 00, usage=100/100 | integration | Проверка срока вместе с финансовым состоянием в БД |
| A-BE-05 | Последняя секунда месяца | 00, usage=100/100 | integration | Проверка срока вместе с финансовым состоянием в БД |
| A-BE-06 | Первая секунда следующего месяца | 54, usage=0 | integration | Проверка срока вместе с финансовым состоянием в БД |
| A-BM-01 | Месячный лимит: 99 при остатке 100 | M=600, monthlyUsed=500; 00, monthlyUsed=599 | integration | Проверка накопленной месячной суммы в БД |
| A-BM-02 | Месячный лимит: 100 при остатке 100 | M=600, monthlyUsed=500; 00, monthlyUsed=600 | integration | Проверка накопленной месячной суммы в БД |
| A-BM-03 | Месячный лимит: 101 при остатке 100 | M=600, monthlyUsed=500; 61 с учётом разных дней | integration | Проверка накопленной месячной суммы в БД |
| A-PW-01 | PW 01: EXPIRED, 1126, POS, MCC 5812 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-02 | PW 02: BLOCKED, 1026, ATM, MCC 5411 | DECLINED, CARD_BLOCKED; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-03 | PW 03: INACTIVE, 1126, ECOM, MCC 5411 | DECLINED, CARD_INACTIVE; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-04 | PW 04: ACTIVE, 1126, ATM, MCC 5812 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-05 | PW 05: INACTIVE, 1026, POS, MCC 5732 | DECLINED, CARD_INACTIVE; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-06 | PW 06: ACTIVE, 1026, ECOM, MCC 5732 | DECLINED/51; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-07 | PW 07: INACTIVE, 1126, ATM, MCC 4722 | DECLINED, CARD_INACTIVE; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-08 | PW 08: BLOCKED, 1126, ECOM, MCC 5812 | DECLINED, CARD_BLOCKED; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-09 | PW 09: EXPIRED, 1026, ECOM, MCC 4722 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-10 | PW 10: EXPIRED, 1126, ATM, MCC 5732 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-11 | PW 11: BLOCKED, 1026, POS, MCC 5732 | DECLINED, CARD_BLOCKED; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-12 | PW 12: ACTIVE, 1126, POS, MCC 5411 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-13 | PW 13: ACTIVE, 0926, ATM, MCC 5732 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-14 | PW 14: ACTIVE, 1026, ECOM, MCC 5812 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-15 | PW 15: ACTIVE, 1026, POS, MCC 4722 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-16 | PW 16: ACTIVE, 1126, ATM, MCC 4722 | DECLINED/51; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-17 | PW 17: ACTIVE, 0926, ECOM, MCC 5812 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-18 | PW 18: ACTIVE, 1026, POS, MCC 5812 | APPROVED/00; B=1; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-19 | PW 19: ACTIVE, 1126, ATM, MCC 4722 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-20 | PW 20: INACTIVE, 1126, POS, MCC 5812 | DECLINED, CARD_INACTIVE; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-21 | PW 21: ACTIVE, 1026, POS, MCC 5732 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-22 | PW 22: ACTIVE, 1126, POS, MCC 5411 | DECLINED/51; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-23 | PW 23: EXPIRED, 1026, ECOM, MCC 5411 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-24 | PW 24: ACTIVE, 0926, POS, MCC 4722 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-25 | PW 25: ACTIVE, 1026, ECOM, MCC 5411 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-26 | PW 26: ACTIVE, 1026, POS, MCC 5812 | DECLINED/51; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-27 | PW 27: ACTIVE, 0926, ECOM, MCC 5411 | DECLINED/54; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-28 | PW 28: ACTIVE, 1026, POS, MCC 5732 | DECLINED/61; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| A-PW-29 | PW 29: BLOCKED, 1126, ATM, MCC 4722 | DECLINED, CARD_BLOCKED; контроль usage и баланса | integration | Финансовое состояние сверяется с БД после HTTP |
| C-01 | Создание карты | Поля, PAN с Луна, срок и сохранённая карта | integration | HTTP-ответ и GET сверяются с cards |
| C-02 | Неизвестный BIN | 404 и отсутствие новой карты | integration | Сверка bin_issuers и числа сохранённых карт |
| C-03 | Буква в BIN | 400 без создания карты | integration | Общие шаги C включают проверку БД |
| C-04 | Пустой BIN | 400 без создания карты | integration | Общие шаги C включают проверку БД |
| C-05 | Буква в PAN | 400 на GET некорректного PAN | integration | Сохранена процедура C со сверкой БД |
| C-06 | Отсутствующий PAN с неверной Луна | 404 для 16 цифр без проверки Луна на GET | integration | Отсутствие карты подтверждается в БД |
| C-07 | Строчные буквы в имени | 400 без создания карты | integration | Общие шаги C включают проверку БД |
| C-08 | Валюта RUB вместо цифровой строки | 400 без создания карты | integration | Общие шаги C включают проверку БД |
| C-09 | Пустой список BIN генератора | 400 без новых карт | integration | Контроль числа карт в БД |
| C-10 | Фильтр ACTIVE | В выборке только ACTIVE, total=2 | integration | Ответ сверяется с подготовленным набором в cards |
| C-11 | Фильтр INACTIVE | В выборке только INACTIVE, total=2 | integration | Ответ сверяется с подготовленным набором в cards |
| C-12 | Фильтр BLOCKED | В выборке только BLOCKED, total=2 | integration | Ответ сверяется с подготовленным набором в cards |
| C-13 | Фильтр EXPIRED | В выборке только EXPIRED, total=2 | integration | Ответ сверяется с подготовленным набором в cards |
| C-14 | Offset за концом списка | Пустая страница при total=3 | integration | Пагинация сверяется с сохранённым набором |
| C-15 | Пагинация по умолчанию | 50 карт из 51 по ТЗ | integration | HTTP-страница сверяется с числом карт в БД |
| C-16 | PATCH дневного лимита | Меняется только dailyLimit | integration | Проверяется сохранённое изменение cards |
| C-17 | Мягкое удаление | DELETED в БД, отсутствие в GET/списке и отказ авторизации | integration | Проверяются SQL-скрытие записи и связь CMS → Authorization |
| C-18 | Генерация 1000 карт | Распределение BIN, PAN, сроки и диапазоны сумм | integration | Ответ генератора сверяется с сохранёнными картами |
| C-19 | Нулевая сумма резерва | 400, баланс прежний | integration | Контроль отсутствия финансовых изменений в БД |
| C-20 | Отрицательная сумма резерва | 400, баланс прежний | integration | Контроль отсутствия финансовых изменений в БД |
| C-21 | Повторный reserve с тем же RRN | 409 без второго списания | integration | Одна запись reservations и баланс после двух запросов |
| C-22 | RRN из 11 цифр | 400 без резерва | integration | Проверка баланса и записей резерва |
| C-23 | PATCH месячного лимита | Меняется только monthlyLimit | integration | Проверяется сохранённое изменение cards |
| C-24 | PATCH баланса | Меняется только availableBalance | integration | Проверяется сохранённое изменение cards |
| C-25 | Статус и BIN одновременно | Пересечение двух фильтров | integration | Результат SQL-выборки сверяется с набором карт |
| C-26 | Второй BIN, дефис и USD | Успешное создание с нулевыми суммами | integration | Поля ответа и сохранённой карты сверяются по шагам C |
| C-BE-01 | Создание перед сменой года | 31.12.2026 23:59:59 → expiry 1229 | integration | Сверка срока в ответе, GET и cards |
| C-BE-02 | Создание в начале года | 01.01.2027 00:00:00 → expiry 0130 | integration | Сверка срока в ответе, GET и cards |
| C-BE-03 | Создание после начала года | 01.01.2027 00:00:01 → expiry 0130 | integration | Сверка срока в ответе, GET и cards |
| C-BG-01 | Генерация count=0 | 400, новых карт нет | integration | Количество новых записей в cards |
| C-BG-02 | Генерация count=1 | 201, одна карта | integration | Количество новых записей в cards |
| C-BG-03 | Генерация count=2 | 201, две карты | integration | Количество новых записей в cards |
| C-BG-04 | Генерация count=9999 | 201, generated=9999 | integration | Количество новых записей в cards |
| C-BG-05 | Генерация count=10000 | 201, generated=10000 | integration | Количество новых записей в cards |
| C-BG-06 | Генерация count=10001 | 400, новых карт нет | integration | Количество новых записей в cards |
| C-BI-01 | BIN из 5 цифр | 400 без создания карты | integration | Проверка создания и сохранённых карт по шагам C |
| C-BI-02 | BIN из 6 цифр | 201, корректный PAN | integration | Проверка создания и сохранённых карт по шагам C |
| C-BI-03 | BIN из 7 цифр | 400 без создания карты | integration | Проверка создания и сохранённых карт по шагам C |
| C-BL-01 | Limit=0 | 400 | integration | Ответ сверяется с сохранённым набором карт |
| C-BL-02 | Limit=1 | Одна карта, total=3 | integration | Ответ сверяется с сохранённым набором карт |
| C-BL-03 | Limit=2 | Две карты, total=3 | integration | Ответ сверяется с сохранённым набором карт |
| C-BO-01 | Offset=-1 | 400 | integration | Пагинация сверяется с сохранённым набором карт |
| C-BO-02 | Offset=0 | Три карты, total=3 | integration | Пагинация сверяется с сохранённым набором карт |
| C-BO-03 | Offset=1 | Две карты, total=3 | integration | Пагинация сверяется с сохранённым набором карт |
| C-BP-01 | PAN из 15 цифр | 400 на GET | integration | Сохранена процедура C со сверкой GET и БД |
| C-BP-02 | PAN из 16 цифр | 200, ранее созданная карта | integration | Сохранена процедура C со сверкой GET и БД |
| C-BP-03 | PAN из 17 цифр | 400 на GET | integration | Сохранена процедура C со сверкой GET и БД |
| C-BR-01 | Резерв 99 при B=100 | 200, B=1 | integration | Изменение cards и reservations в одной операции |
| C-BR-02 | Резерв 100 при B=100 | 200, B=0 | integration | Изменение cards и reservations в одной операции |
| C-BR-03 | Резерв 101 при B=100 | 402, B=100, резерва нет | integration | Изменение cards и reservations в одной операции |
| CM-PW-01 | PW 01: создание карты | 400; формат BIN | integration | Контроль отсутствия новых карт в БД |
| CM-PW-02 | PW 02: создание карты | 201; карта создана со всеми переданными значениями | integration | Проверка сохранения полей карты |
| CM-PW-03 | PW 03: создание карты | 400; monthlyLimit < 0 | integration | Контроль отсутствия новых карт в БД |
| CM-PW-04 | PW 04: создание карты | 400; пустое имя | integration | Контроль отсутствия новых карт в БД |
| CM-PW-05 | PW 05: создание карты | 400; пустое имя | integration | Контроль отсутствия новых карт в БД |
| CM-PW-06 | PW 06: создание карты | 400; формат BIN | integration | Контроль отсутствия новых карт в БД |
| CM-PW-07 | PW 07: создание карты | 400; формат BIN | integration | Контроль отсутствия новых карт в БД |
| CM-PW-08 | PW 08: создание карты | 400; dailyLimit < 0 | integration | Контроль отсутствия новых карт в БД |
| CM-PW-09 | PW 09: создание карты | 404; неизвестный BIN | integration | Контроль отсутствия новых карт в БД |
| CM-PW-10 | PW 10: создание карты | 400; длина валюты | integration | Контроль отсутствия новых карт в БД |
| CM-PW-11 | PW 11: создание карты | 404; неизвестный BIN | integration | Контроль отсутствия новых карт в БД |
| CM-PW-12 | PW 12: создание карты | 400; длина валюты | integration | Контроль отсутствия новых карт в БД |
| CM-PW-13 | PW 13: создание карты | 400; dailyLimit < 0 | integration | Контроль отсутствия новых карт в БД |
| CM-PW-14 | PW 14: создание карты | 400; monthlyLimit < 0 | integration | Контроль отсутствия новых карт в БД |
| CM-PW-15 | PW 15: создание карты | 400; длина валюты | integration | Контроль отсутствия новых карт в БД |
| CM-PW-16 | PW 16: создание карты | 400; dailyLimit < 0 | integration | Контроль отсутствия новых карт в БД |

## 3. Пропорции

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 0 | 4 | 4 | 3,01% |
| api | 0 | 3 | 3 | 2,26% |
| integration | 119 | 5 | 124 | 93,23% |
| e2e | 0 | 2 | 2 | 1,50% |
| **Всего** | **119** | **14** | **133** | **100%** |

В объединённой группе api/integration: 119 старых + 8 новых = **127 кейсов (95,49%)**. Unit — 3,01%, E2E — 1,50%.

Большая доля api/integration получилась из-за исходного тест-дизайна: он проверяет ответы вместе с сохранёнными картами, резервами и usage. В новых кейсах отдельно выделены четыре проверки логики без стенда, три HTTP-контракта, пять связей с БД/брокером и два сквозных пути.

Перекоса в E2E нет, но unit-проверок пока мало. Для автоматизации стоит дополнить набор изолированными проверками статуса, срока, расчётов и генерации PAN. Это сократит число ошибок, которые впервые обнаруживаются на стенде с БД.
