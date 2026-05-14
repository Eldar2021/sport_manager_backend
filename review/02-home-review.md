# Home (Venue + Table) Review

Doc kaynağı: [docs/home_page_api.md](../docs/home_page_api.md)  
Kod: [HomePageController](../src/main/java/kg/sportmanager/controller/HomePageController.java), [HomeServiceImpl](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java), [Venue](../src/main/java/kg/sportmanager/entity/Venue.java), [Tables](../src/main/java/kg/sportmanager/entity/Tables.java), [HomeMapper](../src/main/java/kg/sportmanager/mapper/HomeMapper.java), [VenueRepository](../src/main/java/kg/sportmanager/repository/VenueRepository.java), [TableRepository](../src/main/java/kg/sportmanager/repository/TableRepository.java)

---

## Endpoint Uyum Matrisi

| Doc                            | Kod                      | Auth Doc      | Kod                | Body           | Uyum                |
| ------------------------------ | ------------------------ | ------------- | ------------------ | -------------- | ------------------- |
| `GET /api/v1/venue/list`       | `GET /api/v1/venue/list` | Owner+Manager | resolveOwner kırık | ✅ format      | ⚠️ Manager için boş |
| `GET /api/v1/venue/selected`   | aynı                     | Both          | aynı sorun         | ✅ format      | ⚠️ Manager için 404 |
| `PATCH /api/v1/venue/selected` | aynı                     | Both          | aynı sorun         | ✅ format      | ⚠️ Manager için 404 |
| `POST /api/v1/venue/create`    | aynı                     | OWNER         | `requireOwner` ✅  | ✅             | ✅                  |
| `PUT /api/v1/venue/{id}`       | aynı                     | OWNER         | `requireOwner` ✅  | ✅             | ✅                  |
| `DELETE /api/v1/venue/{id}`    | aynı                     | OWNER         | ✅                 | DeleteResponse | ✅                  |
| `POST /api/v1/table/create`    | aynı                     | OWNER         | ✅                 | ✅             | ✅                  |
| `PUT /api/v1/table/{id}`       | aynı                     | OWNER         | ✅                 | ✅             | ✅                  |
| `DELETE /api/v1/table/{id}`    | aynı                     | OWNER         | ✅                 | DeleteResponse | ✅                  |

Yol prefix'i doğru (`/api/v1/...`). Auth roller doğru taraflarda yönlendirilmiş. **Asıl sorun:** manager rolünde resolve mantığı kırık.

---

## P0 — Kritik

### 1. Manager için tüm `/venue/*` ve `/table/*` GET endpoint'leri kırık

[HomeServiceImpl.resolveOwner:248-252](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L248-L252):

```java
private User resolveOwner(User user) {
    // В текущей схеме manager привязан к owner через InviteCode.
    // Если у вас есть поле owner в User — верните его. Пока возвращаем самого user.
    return user;
}
```

Junior dev kendisi de açıkça yazmış: "Şu an user'ı kendini döndürüyor". Sonuç:

- `GET /venue/list` → manager için `venueRepository.findByOwner(manager)` → boş array.
- `GET /venue/selected` → boş → `autoSelectOldest` → `VENUE_NOT_FOUND` (manager'ın hiç venue'si yok).
- `PATCH /venue/selected` → manager kendi venue ID'sini geçemiyor (owner'a ait), `VENUE_NOT_FOUND`.

Doc [home_page_api.md §1 Notes](../docs/home_page_api.md):

> Manager için: bağlı olduğu owner'ın tüm mekanları.

**Çözüm yolu** (auth gap'i ile birlikte):

1. `User` entity'sine `@ManyToOne(fetch=LAZY) @JoinColumn(name="owner_id") private User owner;` ekle.
2. `AuthServiceImpl.register` Manager yaratımında `user.setOwner(invite.getOwner())`.
3. `HomeServiceImpl.resolveOwner`:

```java
private User resolveOwner(User user) {
    return user.getRole() == User.Role.OWNER ? user : user.getOwner();
}
```

4. Aynı düzeltme [SessionServiceImpl.validateTableAccess](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252)'da da gerekli (bkz. [03-session-review.md](03-session-review.md)).

---

## P1 — Yüksek

### 2. `PATCH /venue/selected` race condition

[HomeServiceImpl.updateSelectedVenue:60-78](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L60-L78):

```java
venueRepository.findByOwnerAndSelectedTrueAndDeletedAtIsNull(owner).ifPresent(old -> {
    old.setSelected(false);
    venueRepository.save(old);
});
newSelected.setSelected(true);
venueRepository.save(newSelected);
```

İki paralel PATCH gelirse: ikisi de eski seçili'yi false yapar, ikisi de farklı venue'leri true yapar → **iki seçili venue**. `@Transactional` var ama default isolation READ_COMMITTED, lock yok.

**Düzeltme**: tek UPDATE statement ile çöz:

```java
venueRepository.clearSelectedForOwner(owner.getId()); // @Modifying @Query("UPDATE Venue v SET v.selected=false WHERE v.owner.id=:ownerId")
venueRepository.markSelected(newSelected.getId());
```

Veya unique partial index: `CREATE UNIQUE INDEX ON venues (owner_id) WHERE selected = true AND deleted_at IS NULL;`. İkinci PATCH constraint violation alır.

### 3. N+1 sorgu: `getVenueList` her venue için tablo listesi yüklüyor

[HomeServiceImpl.getVenueList:37-45](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L37-L45):

```java
return venues.stream().map(v -> {
    int count = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(v).size();
    return mapper.toVenueResponse(v, count);
}).toList();
```

20 venue → 20 ayrı sorgu, her biri tüm tablo satırlarını yüklüyor (sadece sayma için). Düzeltme:

```java
long countByVenueAndDeletedAtIsNull(Venue venue);  // TableRepository'ye ekle
```

Daha iyisi: tek sorguyla `Map<UUID, Long>` döndür:

```java
@Query("SELECT t.venue.id, COUNT(t) FROM Tables t WHERE t.venue.owner=:owner AND t.deletedAt IS NULL GROUP BY t.venue.id")
List<Object[]> countTablesByVenueForOwner(User owner);
```

### 4. N+1 sorgu: `buildSelectedVenueResponse` her masa için aktif session ayrı sorgu

[HomeServiceImpl.buildSelectedVenueResponse:227-238](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L227-L238):

```java
List<TableResponse> tableResponses = mapper.toTableResponseList(tables,
        t -> sessionRepository.findByTableAndIsActiveTrue(t).orElse(null));
```

50 masa → 50 ayrı `SELECT ... FROM sessions ... WHERE table=? AND isActive=true`. Düzeltme: `findActiveByTablesIn(List<Tables>)` ile tek sorgu + Map<TableId, Session>.

### 5. `validateTableUpdateRequest` venue change'i engellemiyor

[HomePageController.updateTable:99-105](../src/main/java/kg/sportmanager/controller/HomePageController.java#L99-L105) `TableRequest` alıyor — `venueId` field'ı var. Service `updateTable` venueId'yi tamamen ignore ediyor (OK doc'a göre: "Masa başka bir mekana taşınamaz"). Ama:

- DTO temizliği için `TableRequest`'i `CreateTableRequest`/`UpdateTableRequest` olarak ayır.
- Update'te venueId gelirse explicit `VALIDATION_ERROR` döndür.

### 6. `tableNumber` unique constraint sadece soft-delete dışı kontrolü

[TableRepository.existsByVenueAndNumberAndDeletedAtIsNull](../src/main/java/kg/sportmanager/repository/TableRepository.java#L18) doğru. **Ama:** DB constraint yok. Race condition: iki paralel create aynı number ile → ikisi de exists check'i geçer, ikisi de insert. Düzeltme: partial unique index:

```sql
CREATE UNIQUE INDEX ON tables (venue_id, number) WHERE deleted_at IS NULL;
```

Aynı sorun `venues.number` için de var (owner içinde unique).

### 7. Delete venue: cascade soft-delete vs `VENUE_HAS_TABLES`

Docs çelişkili:

> | `VENUE_HAS_TABLES` | 409 | Mekanın içinde masalar var, önce onları sil |  
> Silinen mekanın masaları da soft delete edilir (cascade).

Code [HomeServiceImpl.deleteVenue:131-156](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L131-L156) cascade yolunu seçmiş ve `VENUE_HAS_TABLES` hiç fırlatılmıyor. **Kod tutarlı**, doc'taki iki ifade çelişiyor. Doc'tan `VENUE_HAS_TABLES` error code satırını silmek gerek, **veya** kodu "tablo varsa 409 dön" şeklinde değiştir. Bu iş kararı.

### 8. `tarifAmount` `0` olmamalı, kod doğru ama `negative` `BigDecimal` yok

[HomeServiceImpl.validateTableUpdateRequest:307-309](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L307-L309) `< 1 || > 1_000_000` kontrolü doğru, doc kontratıyla uyumlu. Tek not: `Integer` tip kullanıldığı için max 2.1 milyar; sınır 1M, OK.

---

## P2 — Orta

### 9. Doc'taki "Detail" alanı eksik

Docs:

```json
{
  "code": "VALIDATION_ERROR",
  "details": [{ "field": "name", "rule": "max_length", "message": "..." }]
}
```

Kod sadece `code` döner. Validation `details` array'i hiçbir yerde dolmuyor. Mobil hangi alanın yanlış olduğunu göremiyor → kullanıcı "Validation failed" görür. Düzeltme: `ErrorResponse`'a `details: List<ValidationError>` ekle, Jakarta `@Valid` + `MethodArgumentNotValidException` handler kullan.

### 10. Tarih formatı `.toString()` ile inconsistent

[HomeMapper:24-25](../src/main/java/kg/sportmanager/mapper/HomeMapper.java#L24-L25):

```java
.createdAt(venue.getCreatedAt().toString())
.updatedAt(venue.getUpdatedAt().toString())
```

`Instant.toString()` → `"2026-04-15T10:30:00Z"` (millisaniyesiz). Doc örnekleri `"2026-04-15T10:30:00.000Z"` (milisaniyeli). Mobil ISO 8601 parser'ı her ikisini de kabul eder genelde, ama tutarsızlık. Daha iyisi: DTO'da `Instant` tip kullan, Jackson serialize etsin (`spring.jackson.serialization.write-dates-as-timestamps=false` zaten default false).

Aynı sorun `TableResponse.createdAt/updatedAt`, `SessionResponse` tüm tarih alanları.

### 11. `selected` auto-set GET içinde state değişiyor

[HomeServiceImpl.getSelectedVenue:50-56](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L50-L56) → `autoSelectOldest` set ediyor ve persist ediyor. GET isteği state mutasyonu yapıyor. İdempotency açısından kabul edilebilir (sonuç deterministic) ama HTTP semantiği gereği `GET` side-effect'siz olmalı.

Alternatif: ilk venue oluşturulduğunda `selected: true` set et ([createVenue:91-99](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L91-L99) zaten yapıyor) ve `delete` sırasında otomatik switch yap ([deleteVenue:144-152](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L144-L152) zaten yapıyor). O zaman GET'te auto-select gerekmez.

### 12. `parseUuid` her validation hatasını VALIDATION_ERROR yapıyor

[HomeServiceImpl.parseUuid:276-282](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L276-L282) yanlış formatlı UUID için `VALIDATION_ERROR 422` döner. Docs'ta path param bozuksa 400 BAD_REQUEST mantıklı. Minor.

### 13. `VenueRequest.name` blank check `length > 100` doğru ama min length kontrolü "1" sayılıyor

Doc: 1-100. Kod: `isBlank()` → boşsa ret, `length > 100` → fazlaysa ret. `length == 0` zaten blank. OK fakat: `"   "` (3 boşluk) `isBlank() = true` → ret. Doğru. Tek karakter `"x"` geçer. Doc kontratıyla uyumlu.

### 14. Currency enum'da `KGZ` typo riski yok

[Tables.java:68-70](../src/main/java/kg/sportmanager/entity/Tables.java#L68-L70):

```java
public enum Currency { KGS, USD, RUB, KZT, TRY }
```

Doc'la birebir aynı. ✅

`TRY` Java keyword değil (lower-case `try` keyword), enum constant olarak kullanılabilir. OK.

### 15. Tables entity ismi çoğul

`@Entity @Table(name="tables") class Tables` — Java tarafı çoğul tip ismi, JPA tarafı `tables` tablosu. Sınıf adının çoğul olması Java konvansiyonu ihlali. `RoomTable` veya `PoolTable` daha iyi. Sadece naming.

### 16. `Venue.selected` boolean primitive

[Venue.java:41](../src/main/java/kg/sportmanager/entity/Venue.java#L41):

```java
@Column(nullable = false)
private boolean selected = false;
```

Lombok `@Data` ile `isSelected()` getter üretir. JSON serialization Jackson default: `selected` field'ı. Doc: `"selected": true`. OK. `Boolean` (boxed) gerek yok çünkü null anlam taşımıyor.

---

## P3 — Düşük

- `HomeServiceImpl.findVenueOwnedBy` filtre yapısı `.filter().orElseThrow()` zincirleme `Optional` üzerinde — owner mismatch durumunda `VENUE_NOT_FOUND` döner (`FORBIDDEN` değil). Güvenlik açısından bu doğru — başka owner'a ait varlığın varlığını ifşa etme. ✅
- `findTableOwnedBy` ise aynı durumda `FORBIDDEN` döner ([HomeServiceImpl:266-274](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L266-L274)). **Tutarsızlık** — biri `404` öbürü `403`. Aynı politika seç (önerim: ikisi de `404 VENUE_NOT_FOUND`/`TABLE_NOT_FOUND` — leak yok).
- `tableRepository.existsByVenueAndDeletedAtIsNull` tanımlı ama hiç kullanılmıyor — dead code.
- Soft delete cascade sırası: önce tables update edilip kaydediliyor, sonra venue update. Eğer venue save fail ederse tables orphan kalır (cascade tx olduğu için aslında rollback olur — `@Transactional` var). OK.
- `mapper.toTableResponseList` `Function<Tables, Session>` callback'i alıyor → her masa için ayrı session fetch. N+1'in yapısal kaynağı. Reformat.

---

## Eylem Önerileri

1. **`User.owner` ekle**, register'da set et, `resolveOwner` düzelt — manager UX'i unblock olur.
2. **`PATCH /venue/selected`** için unique partial index veya `@Modifying` query.
3. **N+1 sorguları çöz** — `countByVenueAndDeletedAtIsNull` ekle, active session map'i hazırla.
4. **`@Valid` + Jakarta validation** kullan, `details` array'ini doldur.
5. **`Tables` → `RoomTable`** rename (opsiyonel, naming).
6. **DateTime için DTO'da `Instant`** kullan, `.toString()` çağrılarını sil.
7. **`Venue.number`, `Tables.number` için partial unique index** — race condition önle.
8. **`getSelectedVenue` GET'i pure'lat** — state mutasyon'unu create/delete'e taşı.
9. **Doc'taki `VENUE_HAS_TABLES` çelişkisi** — owner ile karar ver: cascade mi yoksa "önce sil" mi.
