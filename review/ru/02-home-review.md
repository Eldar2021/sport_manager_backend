# Ревью Home (Venue + Table)

Источник: [docs/home_page_api.md](../../docs/home_page_api.md)  
Код: [HomePageController](../../src/main/java/kg/sportmanager/controller/HomePageController.java), [HomeServiceImpl](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java), [Venue](../../src/main/java/kg/sportmanager/entity/Venue.java), [Tables](../../src/main/java/kg/sportmanager/entity/Tables.java), [HomeMapper](../../src/main/java/kg/sportmanager/mapper/HomeMapper.java), [VenueRepository](../../src/main/java/kg/sportmanager/repository/VenueRepository.java), [TableRepository](../../src/main/java/kg/sportmanager/repository/TableRepository.java)

---

## Матрица соответствия эндпоинтов

| Doc                            | Код                      | Auth Doc      | Код                 | Body           | Соответствие           |
| ------------------------------ | ------------------------ | ------------- | ------------------- | -------------- | ---------------------- |
| `GET /api/v1/venue/list`       | `GET /api/v1/venue/list` | Owner+Manager | resolveOwner сломан | ✅ формат      | ⚠️ Пусто для менеджера |
| `GET /api/v1/venue/selected`   | то же                    | Both          | та же проблема      | ✅ формат      | ⚠️ 404 для менеджера   |
| `PATCH /api/v1/venue/selected` | то же                    | Both          | та же проблема      | ✅ формат      | ⚠️ 404 для менеджера   |
| `POST /api/v1/venue/create`    | то же                    | OWNER         | `requireOwner` ✅   | ✅             | ✅                     |
| `PUT /api/v1/venue/{id}`       | то же                    | OWNER         | `requireOwner` ✅   | ✅             | ✅                     |
| `DELETE /api/v1/venue/{id}`    | то же                    | OWNER         | ✅                  | DeleteResponse | ✅                     |
| `POST /api/v1/table/create`    | то же                    | OWNER         | ✅                  | ✅             | ✅                     |
| `PUT /api/v1/table/{id}`       | то же                    | OWNER         | ✅                  | ✅             | ✅                     |
| `DELETE /api/v1/table/{id}`    | то же                    | OWNER         | ✅                  | DeleteResponse | ✅                     |

Префикс пути правильный (`/api/v1/...`). Auth-роли разведены корректно. **Главная проблема:** логика resolve для роли MANAGER сломана.

---

## P0 — Критично

### 1. Все GET `/venue/*` и `/table/*` для менеджера не работают

[HomeServiceImpl.resolveOwner:248-252](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L248-L252):

```java
private User resolveOwner(User user) {
    // В текущей схеме manager привязан к owner через InviteCode.
    // Если у вас есть поле owner в User — верните его. Пока возвращаем самого user.
    return user;
}
```

Junior сам это и зафиксировал в комментарии: «Пока возвращаем самого user». Итог:

- `GET /venue/list` → для менеджера `venueRepository.findByOwner(manager)` → пустой массив.
- `GET /venue/selected` → пусто → `autoSelectOldest` → `VENUE_NOT_FOUND` (у менеджера нет своих залов).
- `PATCH /venue/selected` → менеджер не может передать ID своего зала (принадлежит владельцу) → `VENUE_NOT_FOUND`.

Docs [home_page_api.md §1 Notes](../../docs/home_page_api.md):

> Для менеджера: все залы владельца, к которому он привязан.

**План исправления** (в связке с auth):

1. Добавить в `User` поле `@ManyToOne(fetch=LAZY) @JoinColumn(name="owner_id") private User owner;`.
2. В `AuthServiceImpl.register` при создании Manager — `user.setOwner(invite.getOwner())`.
3. `HomeServiceImpl.resolveOwner`:

```java
private User resolveOwner(User user) {
    return user.getRole() == User.Role.OWNER ? user : user.getOwner();
}
```

4. То же исправление нужно в [SessionServiceImpl.validateTableAccess](../../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252) (см. [03-session-review.md](03-session-review.md)).

---

## P1 — Высокий

### 2. Race condition в `PATCH /venue/selected`

[HomeServiceImpl.updateSelectedVenue:60-78](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L60-L78):

```java
venueRepository.findByOwnerAndSelectedTrueAndDeletedAtIsNull(owner).ifPresent(old -> {
    old.setSelected(false);
    venueRepository.save(old);
});
newSelected.setSelected(true);
venueRepository.save(newSelected);
```

Два параллельных PATCH: оба сбросят старый selected, оба пометят разные venue как true → **два selected**. `@Transactional` есть, но изоляция по умолчанию READ_COMMITTED, lock'а нет.

**Исправление**: одним UPDATE-стейтментом:

```java
venueRepository.clearSelectedForOwner(owner.getId()); // @Modifying @Query("UPDATE Venue v SET v.selected=false WHERE v.owner.id=:ownerId")
venueRepository.markSelected(newSelected.getId());
```

Или unique partial index: `CREATE UNIQUE INDEX ON venues (owner_id) WHERE selected = true AND deleted_at IS NULL;`. Второй PATCH получит constraint violation.

### 3. N+1: `getVenueList` грузит таблицу столов на каждый зал

[HomeServiceImpl.getVenueList:37-45](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L37-L45):

```java
return venues.stream().map(v -> {
    int count = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(v).size();
    return mapper.toVenueResponse(v, count);
}).toList();
```

20 залов → 20 запросов, каждый грузит все строки таблиц (только ради count). Исправление:

```java
long countByVenueAndDeletedAtIsNull(Venue venue);  // в TableRepository
```

Ещё лучше — одним запросом `Map<UUID, Long>`:

```java
@Query("SELECT t.venue.id, COUNT(t) FROM Tables t WHERE t.venue.owner=:owner AND t.deletedAt IS NULL GROUP BY t.venue.id")
List<Object[]> countTablesByVenueForOwner(User owner);
```

### 4. N+1: `buildSelectedVenueResponse` грузит активную сессию для каждого стола

[HomeServiceImpl.buildSelectedVenueResponse:227-238](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L227-L238):

```java
List<TableResponse> tableResponses = mapper.toTableResponseList(tables,
        t -> sessionRepository.findByTableAndIsActiveTrue(t).orElse(null));
```

50 столов → 50 отдельных `SELECT ... FROM sessions ... WHERE table=? AND isActive=true`. Исправление: `findActiveByTablesIn(List<Tables>)` одним запросом + Map<TableId, Session>.

### 5. `validateTableUpdateRequest` не блокирует смену venue

[HomePageController.updateTable:99-105](../../src/main/java/kg/sportmanager/controller/HomePageController.java#L99-L105) принимает `TableRequest` с полем `venueId`. Сервис `updateTable` его полностью игнорирует (что соответствует docs: «Стол нельзя перенести в другой зал»). Но:

- Для чистоты DTO разделить `TableRequest` на `CreateTableRequest` и `UpdateTableRequest`.
- При update, если пришёл venueId — явно отдавать `VALIDATION_ERROR`.

### 6. Уникальность `tableNumber` только через soft-delete-aware проверку

[TableRepository.existsByVenueAndNumberAndDeletedAtIsNull](../../src/main/java/kg/sportmanager/repository/TableRepository.java#L18) — OK. **Но:** DB-констрейнта нет. Race condition: два параллельных create с одним number — оба проходят exists-check, оба делают insert. Исправление: partial unique index:

```sql
CREATE UNIQUE INDEX ON tables (venue_id, number) WHERE deleted_at IS NULL;
```

Та же проблема с `venues.number` (uniq в рамках владельца).

### 7. Delete venue: cascade soft-delete vs `VENUE_HAS_TABLES`

Docs противоречивы:

> | `VENUE_HAS_TABLES` | 409 | В зале есть столы, сначала удалите их |  
> При удалении зала его столы также мягко удаляются (cascade).

Код [HomeServiceImpl.deleteVenue:131-156](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L131-L156) идёт по cascade-пути, и `VENUE_HAS_TABLES` никогда не бросается. **Код консистентен**, противоречие — в docs. Нужно либо убрать `VENUE_HAS_TABLES` из docs, **либо** менять код на «есть столы → 409». Бизнес-решение.

### 8. `tarifAmount` `0` запрещён — код верный, но нет проверки на отрицательные `BigDecimal`

[HomeServiceImpl.validateTableUpdateRequest:307-309](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L307-L309) `< 1 || > 1_000_000` — корректно, соответствует контракту. Единственное замечание: `Integer` даёт максимум 2.1 млрд, верхняя граница 1М — OK.

---

## P2 — Средний

### 9. В docs описан `details`, в коде его нет

Docs:

```json
{
  "code": "VALIDATION_ERROR",
  "details": [{ "field": "name", "rule": "max_length", "message": "..." }]
}
```

Код возвращает только `code`. Массив `details` нигде не заполняется. Мобильное приложение не знает, какое поле невалидно → пользователь видит «Validation failed». Исправление: добавить `details: List<ValidationError>` в `ErrorResponse`, использовать Jakarta `@Valid` + обработчик `MethodArgumentNotValidException`.

### 10. Несогласованный формат дат через `.toString()`

[HomeMapper:24-25](../../src/main/java/kg/sportmanager/mapper/HomeMapper.java#L24-L25):

```java
.createdAt(venue.getCreatedAt().toString())
.updatedAt(venue.getUpdatedAt().toString())
```

`Instant.toString()` → `"2026-04-15T10:30:00Z"` (без миллисекунд). Примеры в docs — `"2026-04-15T10:30:00.000Z"` (с миллисекундами). Мобильный ISO 8601-парсер обычно принимает оба, но это несогласованность. Лучше: использовать `Instant` в DTO, сериализацию делает Jackson (`spring.jackson.serialization.write-dates-as-timestamps=false` уже default).

Та же проблема в `TableResponse.createdAt/updatedAt`, во всех датах `SessionResponse`.

### 11. `selected` auto-set: GET меняет состояние

[HomeServiceImpl.getSelectedVenue:50-56](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L50-L56) → `autoSelectOldest` устанавливает и сохраняет. GET-запрос мутирует состояние. Идемпотентность сохранена (результат детерминированный), но семантика HTTP требует от GET быть без побочных эффектов.

Альтернатива: проставлять `selected: true` при создании первого зала ([createVenue:91-99](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L91-L99) — уже делает), а при удалении автоматически переключать ([deleteVenue:144-152](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L144-L152) — уже делает). Тогда auto-select в GET не нужен.

### 12. `parseUuid` для любой ошибки парсинга отдаёт VALIDATION_ERROR

[HomeServiceImpl.parseUuid:276-282](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L276-L282) на кривой UUID отдаёт `VALIDATION_ERROR 422`. Для повреждённого path-параметра логичнее 400 BAD_REQUEST. Minor.

### 13. `VenueRequest.name` — blank-check и `length > 100` корректны, минимум — единичная строка

Docs: 1-100. Код: `isBlank()` → пусто = retry, `length > 100` → больше = retry. `length == 0` уже blank — корректно. `"   "` (три пробела) → `isBlank() = true` → отбой. Однобуквенное `"x"` проходит. Контракт docs соблюдён.

### 14. В Currency-enum нет риска опечатки `KGZ`

[Tables.java:68-70](../../src/main/java/kg/sportmanager/entity/Tables.java#L68-L70):

```java
public enum Currency { KGS, USD, RUB, KZT, TRY }
```

Один в один с docs. ✅

`TRY` не Java-keyword (нижний регистр `try` — да), как enum-константа OK.

### 15. Имя сущности `Tables` во множественном числе

`@Entity @Table(name="tables") class Tables` — Java-сторона во множественном, JPA-сторона — таблица `tables`. Имя класса во множественном нарушает Java-конвенции. `RoomTable` или `PoolTable` лучше. Только нейминг.

### 16. `Venue.selected` — boolean primitive

[Venue.java:41](../../src/main/java/kg/sportmanager/entity/Venue.java#L41):

```java
@Column(nullable = false)
private boolean selected = false;
```

Lombok `@Data` даёт getter `isSelected()`. Jackson по умолчанию сериализует как поле `selected`. Docs: `"selected": true`. OK. `Boolean` (boxed) не нужен, null-значение не имеет смысла.

---

## P3 — Низкий

- `HomeServiceImpl.findVenueOwnedBy` — цепочка `.filter().orElseThrow()` поверх `Optional`. При несовпадении владельца возвращает `VENUE_NOT_FOUND` (а не `FORBIDDEN`). С точки зрения безопасности это правильно — не раскрывать существование чужой сущности. ✅
- `findTableOwnedBy` в той же ситуации отдаёт `FORBIDDEN` ([HomeServiceImpl:266-274](../../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L266-L274)). **Несогласованность** — один отдаёт 404, другой 403. Унифицировать (рекомендую оба — `404 VENUE_NOT_FOUND`/`TABLE_NOT_FOUND` — без утечки).
- `tableRepository.existsByVenueAndDeletedAtIsNull` определён, нигде не используется — dead code.
- Порядок cascade-soft-delete: сначала обновляются и сохраняются tables, затем venue. Если save venue упадёт, tables окажутся orphan (но `@Transactional` делает rollback). OK.
- `mapper.toTableResponseList` принимает `Function<Tables, Session>` — каждая таблица отдельно тянет сессию. Структурный источник N+1. Переделать.

---

## Рекомендации

1. **Добавить `User.owner`**, заполнять при регистрации, починить `resolveOwner` — UX менеджера разблокируется.
2. **`PATCH /venue/selected`** — unique partial index или `@Modifying` query.
3. **Решить N+1** — добавить `countByVenueAndDeletedAtIsNull`, построить map активных сессий.
4. **`@Valid` + Jakarta validation**, заполнять массив `details`.
5. **`Tables` → `RoomTable`** rename (опционально, нейминг).
6. **Для DateTime — `Instant` в DTO**, убрать `.toString()`.
7. **Partial unique index для `Venue.number`, `Tables.number`** — устранить race condition.
8. **Сделать `getSelectedVenue` GET-чистым** — перенести мутацию в create/delete.
9. **Противоречие `VENUE_HAS_TABLES` в docs** — решить с владельцем: cascade или «сначала удалите».
