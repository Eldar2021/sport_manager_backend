# Ревью Reports

Источник: [docs/reports-api.md](../../docs/reports-api.md)  
Код: [ReportsController](../../src/main/java/kg/sportmanager/controller/ReportsController.java), [ReportsServiceImpl](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java), [ReportsRepository](../../src/main/java/kg/sportmanager/repository/ReportsRepository.java), [ManagerStatProjection](../../src/main/java/kg/sportmanager/repository/projection/ManagerStatProjection.java) и `dto/response/reports/*`

---

## Матрица соответствия эндпоинтов

| Doc                           | Код | OWNER-only     | Query-параметры                           | Соответствие                       |
| ----------------------------- | --- | -------------- | ----------------------------------------- | ---------------------------------- |
| `GET /reports/venues`         | ✓   | requireOwner ✓ | нет                                       | ✅                                 |
| `GET /reports/overview`       | ✓   | ✓              | все                                       | ⚠️ dead code                       |
| `GET /reports/revenue-series` | ✓   | ✓              | `compare` принимается, но не используется | ⚠️                                 |
| `GET /reports/tables`         | ✓   | ✓              | все                                       | ⚠️ N+1                             |
| `GET /reports/tables/{id}`    | ✓   | ✓              | все                                       | ⚠️ не проверяется venueId mismatch |
| `GET /reports/managers`       | ✓   | ✓              | все, `period` не используется             | ⚠️ username = email                |
| `GET /reports/managers/{id}`  | ✓   | ✓              | все                                       | 🛑 **баг NPE**                     |
| `GET /reports/forecast`       | ✓   | ✓              | все                                       | ⚠️ threshold для YEAR неверный     |

---

## P0 — Критично

### 1. NullPointerException: autounbox в `Long.valueOf(Integer)`

[ReportsServiceImpl.mapToSessionLogEntry:572-587](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L572-L587):

```java
.durationSeconds(Long.valueOf(s.getDurationSeconds()))
.totalAmount(s.getTotalAmount())
```

`Session.durationSeconds` — `Integer`, и **для CANCELLED равен null**. Перегрузки `Long.valueOf(Integer)` нет → вызывается `Long.valueOf(long)` + autounbox → NPE.

**Reproduce:** менеджер отменил сессию, владелец зовёт `/reports/managers/{id}` → endpoint 500.

Исправление:

```java
.durationSeconds(s.getDurationSeconds() != null ? s.getDurationSeconds().longValue() : null)
```

Тип поля DTO `Long` (по контракту с docs) и так допускает `null`.

### 2. Утечка в `resolveManager` через чужого владельца

[ReportsServiceImpl.resolveManager:401-405](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L401-L405):

```java
private User resolveManager(String managerId) {
    return reportsRepository.findManagerById(managerId)
            .orElseThrow(() -> new AppException("MANAGER_NOT_FOUND", ...));
}
```

`findManagerById` — глобальный lookup, нет фильтра по владельцу. Если один владелец передаст managerId другого владельца:

1. `findManagerById` найдёт пользователя → OK.
2. В `sumRevenueByManager(venue, manager, ...)` `venue` — у запрашивающего владельца, `manager` — у другого → revenue=0, sessions=0.
3. Но `name`, `email` (username) менеджера всё равно вернутся.

Итог: **владелец A может узнать имя/email менеджеров владельца B**, угадывая ID. Исправление:

```java
return userRepository.findByIdAndOwner(parseUuid(managerId), currentOwner)
        .orElseThrow(...);
```

(После добавления поля `User.owner`. См. [01-auth-review.md](01-auth-review.md) #8.)

### 3. `getTableDetail` не проверяет принадлежность стола к залу

[ReportsServiceImpl.getTableDetail:177-215](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L177-L215):

```java
Venue venue = resolveVenue(user, venueId);
Tables table = resolveTable(user, tableId);
```

`resolveTable` проверяет, что стол принадлежит владельцу, но **не проверяет, что стол принадлежит этому залу**. Передав `venueId=V1, tableId=T2` (где T2 из другого зала), получим расходящиеся сущности: в summary `venueName=V1`, но heatmap по T2 — несогласованность. Исправление:

```java
if (!table.getVenue().getId().equals(venue.getId())) {
    throw new AppException("TABLE_NOT_FOUND", HttpStatus.NOT_FOUND);
}
```

---

## P1 — Высокий

### 4. `getOverview`: dead code + несогласованный набор полей

[ReportsServiceImpl.getOverview:68-83](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L68-L83):

```java
OverviewResponse.KpiBlock current = OverviewResponse.KpiBlock.builder()...build();  // ← discarded

if (!compare || "TODAY".equalsIgnoreCase(period)) {
    return OverviewResponse.builder()
            .totalRevenue(revenue)
            .totalSessions(sessions)
            .cancelledSessions(cancelled)
            .currency(currency)
            .previous(null)
            .build();
}
```

`current` KpiBlock создаётся и не используется — dead code, удалить.

### 5. В `getRevenueSeries` параметр `compare` не используется

[ReportsController:55-63](../../src/main/java/kg/sportmanager/controller/ReportsController.java#L55-L63) принимает `boolean compare`, но не передаёт в сервис:

```java
return ResponseEntity.ok(reportsService.getRevenueSeries(user, period, from, to, venueId));
```

Осмысленного применения флага `compare` для revenue-series нет (и docs не описывают). Убрать из контроллера либо передать в сервис как no-op.

### 6. В `getManagers` параметр `period` не используется

[ReportsServiceImpl.getManagers:221-242](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L221-L242) принимает `period`, но не использует. Агрегация идёт по `from`/`to` (правильно). `period` и `compare` передаются из контроллера, сервис их игнорирует. Контракт docs не требует `period` для managers (нет блока `previous`). Подчистить сигнатуру.

### 7. В `managerStats` `username` мапится из `email`

[ReportsRepository.managerStats:156-170](../../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L156-L170):

```java
s.manager.email AS username,
```

Docs [managers-api.md](../../docs/managers-api.md#modelsalan-referansı):

```ts
username: string; // без '@'; в UI отображается как '@username'
```

`email` ≠ `username`. На UI `@aibek` превратится в `@john@example.com` → некрасиво. В сущности `User` поля `username` **нет**. **Расхождение схемы.**

Исправление:

1. Добавить в `User` `@Column(unique=true) String username;`.
2. Запросить при регистрации или сгенерировать из local-part email.
3. Поменять проекцию на `s.manager.username AS username`.

### 8. CAST-запрос `findManagerById` — производительность и портируемость

[ReportsRepository:209-210](../../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L209-L210):

```java
@Query("SELECT u FROM User u WHERE CAST(u.id AS string) = :id")
Optional<User> findManagerById(@Param("id") String id);
```

Делает полное сканирование users (индекс не применяется из-за cast). Достаточно `UUID.fromString(id)` + `findById` — идиоматично. Удалить метод, вызвать `userRepository.findById(UUID.fromString(managerId))`.

### 9. N+1 в `getTables`

[ReportsServiceImpl.getTables:146-165](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L146-L165):

```java
for (Tables t : tables) {
    long rev = reportsRepository.sumRevenueByTable(t, from, to);
    long sess = reportsRepository.countCompletedByTable(t, from, to);
    ...
}
```

50 столов → 100 запросов. **Запрос `revenueByTable` (GROUP BY) уже есть** ([строки 90-98](../../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L90-L98)). Аналогично сделать `countByTable` и агрегировать:

```java
@Query("""
    SELECT s.table.id,
           COALESCE(SUM(CASE WHEN s.status='COMPLETED' THEN s.totalAmount ELSE 0 END), 0),
           COUNT(CASE WHEN s.status='COMPLETED' THEN 1 END)
    FROM Session s
    WHERE s.table.venue = :venue AND s.startedAt >= :from AND s.startedAt < :to
    GROUP BY s.table.id
""")
List<Object[]> aggregateByTable(...);
```

Затем по списку столов сджойнить в памяти.

### 10. `buildRevenueSeries`: 2 запроса на каждый bucket (N×2)

[ReportsServiceImpl.buildRevenueSeries:430-443](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L430-L443):

```java
return buckets.stream().map(bucket -> {
    Instant bucketEnd = nextBucket(period, bucket);
    long rev = reportsRepository.sumRevenue(venue, bucket, bucketEnd);
    long sess = reportsRepository.countCompleted(venue, bucket, bucketEnd);
    ...
}).toList();
```

MONTH на 30 дней = 60 запросов. Решается одним SQL c GROUP BY по дню:

```java
@Query("""
    SELECT FUNCTION('DATE_TRUNC', 'day', s.startedAt) AS bucket,
           COALESCE(SUM(s.totalAmount), 0),
           COUNT(s)
    FROM Session s
    WHERE s.table.venue = :venue AND s.status='COMPLETED'
      AND s.startedAt >= :from AND s.startedAt < :to
    GROUP BY FUNCTION('DATE_TRUNC', 'day', s.startedAt)
""")
```

(`date_trunc` в Postgres доступен через FUNCTION в JPQL. Для YEAR — `'month'`.)

### 11. Порог `< 7` в forecast для YEAR

[ReportsServiceImpl.getForecast:298-300](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L298-L300):

```java
if (actualSeries.size() < 7) {
    throw new AppException("NOT_ENOUGH_DATA", ...);
}
```

При YEAR bucket=месяц. В середине мая `actualSeries.size() = 5` (Jan–May) → `NOT_ENOUGH_DATA`. Docs описывают работу forecast и для YEAR (`previousPeriodTotal = full previous year`). Порог должен быть период-зависимым (например, >= 2 месяцев для YEAR).

Исправление:

```java
int minBuckets = "YEAR".equalsIgnoreCase(period) ? 2 : 7;
if (actualSeries.size() < minBuckets) throw ...;
```

### 12. `clippedPrevious` для YEAR считает по DAY

[ReportsServiceImpl.clippedPrevious:488-501](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L488-L501):

```java
long elapsedDays = ChronoUnit.DAYS.between(from, to);
Instant prevFrom = ... minusYears(1);
Instant prevTo = prevFrom.plus(elapsedDays, ChronoUnit.DAYS);
```

Для YEAR логика по сути верная: «тот же диапазон дат прошлого года». Но при вытягивании revenue/series для clipped-previous нужен **тот же размер бакета — месячный**. Сейчас size приходит из `period` → OK, но прозрачнее закомментировать.

### 13. Currency разрешается по первой таблице

[ReportsServiceImpl.resolveCurrency:407-414](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L407-L414):

```java
return tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue)
        .stream().findFirst()
        .map(t -> t.getCurrency().name())
        .orElse("KGS");
```

Docs: «MVP работает в рамках одного зала, поэтому одной валюты достаточно». Но constraint'а нет, в зале могут быть столы в разных валютах. Берётся валюта первого стола — отчёты считаются неверно, если столы разные.

Исправление: либо ввести `currency` на уровне `Venue` и принудительно унифицировать столы, либо в отчёте суммировать по валюте отдельно.

---

## P2 — Средний

### 14. Cast в `revenueByTableRaw` может упасть в рантайме

[ReportsRepository.revenueByTableRaw:99-101](../../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L99-L101):

```java
revenueByTableRaw(venue, from, to).forEach(row -> result.put((UUID) row[0], (Long) row[1]));
```

`SUM(s.totalAmount)` может вернуться как `BigDecimal` или `Long` в зависимости от драйвера. `(Long) row[1]` способен бросить ClassCastException. Безопаснее:

```java
result.put((UUID) row[0], ((Number) row[1]).longValue());
```

Тот же приём нужен и в других проекциях.

### 15. Heatmap в UTC, в docs зона указана как TBD

Docs [reports-api.md §5](../../docs/reports-api.md#5-get-apiv1reportstablesid):

> Hour ordering: ... (в локальном времени; зона зала — при необходимости в v2).

Сейчас UTC. Для Бишкека UTC+6 → вечерние 18:00 в графике превращаются в полуденные 12:00. **Владелец увидит неверный peak.** В v2 ввести `Venue.timezone`.

### 16. `SessionLogEntryResponse.tableNumber` — `int` primitive

[SessionLogEntryResponse:18](../../src/main/java/kg/sportmanager/dto/response/reports/SessionLogEntryResponse.java#L18):

```java
private int tableNumber;
```

`Tables.number` — `Integer` (boxed). В DTO примитив. Autounbox упадёт на null → NPE-риск. Заменить на `Integer`.

### 17. `OverviewResponse.KpiBlock.previous` всегда null, но поле есть

Для рекурсии. OK, но в JSON выводится `"previous": null` → парсер мобилы это переварит. Docs:

```json
"previous": {
  ...,
  "previous": null
}
```

Docs показывают именно так. ✅

### 18. `OverviewResponse` с `@JsonInclude(ALWAYS)` сохраняет null

Docs: при `compare=false` поле `previous: null` остаётся. `@JsonInclude(ALWAYS)` корректен. ✅

### 19. `linearRegression` с нулевым знаменателем

[ReportsServiceImpl.linearRegression:556-570](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L556-L570):

```java
if (denom == 0) return new double[]{0, sumY / n};
```

OK — fallback, когда все x равны. ✅

### 20. `previousPeriodTotal` для MONTH правильно охватывает весь апрель?

`fullPreviousPeriod("MONTH", fromMay)` → 1 апреля → 1 мая. Полный апрель. ✅

### 21. Линейный поиск по `actualSeries` для каждого bucket

[ReportsServiceImpl.getForecast:330-336](../../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L330-L336):

```java
if (actualBuckets.contains(bucket)) {
    long factRev = actualSeries.stream()
        .filter(p -> p.getBucket().equals(bucket))
        .mapToLong(...).findFirst().orElse(0L);
```

Проиндексировать `actualSeries` в `Map<Instant, RevenuePoint>` — lookup O(1).

---

## P3 — Низкий

- `record PeriodRange` вложен в сервис — OK.
- `@DateTimeFormat(iso=ISO.DATE_TIME)` для query-параметров — Spring сам распарсит. ✅
- `RequestParam(defaultValue="true") boolean compare` — OK.
- `requireOwner` повторяется в каждом методе — лучше `@PreAuthorize("hasRole('OWNER')")` через method-level security.
- Комментарии на русском, код на английском. Согласовано.

---

## Рекомендации (без порядка)

1. **Починить NPE `Long.valueOf`** — endpoint перестанет падать.
2. **Добавить фильтр по владельцу в `resolveManager`** — поле `User.owner`.
3. **Кросс-проверка venue↔table в `getTableDetail`**.
4. **Свести N+1 в один SQL** — `getTables`, `buildRevenueSeries`, `buildRevenueSeriesByTable`.
5. **Удалить dead code** — block `current` в `getOverview`, неиспользуемые `compare`/`period`.
6. **Заполнить пробел `username`** — добавить поле в `User`, поменять проекцию.
7. **Удалить CAST в `findManagerById`** — `UUID.fromString` + `findById`.
8. **Сделать порог forecast зависящим от периода**.
9. **Часовой пояс heatmap** — в v2 локализовать через `Venue.timezone`.
10. **Безопасные касты — `((Number) row[1]).longValue()`**.
