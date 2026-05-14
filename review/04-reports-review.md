# Reports Review

Doc kaynağı: [docs/reports-api.md](../docs/reports-api.md)  
Kod: [ReportsController](../src/main/java/kg/sportmanager/controller/ReportsController.java), [ReportsServiceImpl](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java), [ReportsRepository](../src/main/java/kg/sportmanager/repository/ReportsRepository.java), [ManagerStatProjection](../src/main/java/kg/sportmanager/repository/projection/ManagerStatProjection.java) ve `dto/response/reports/*`

---

## Endpoint Uyum Matrisi

| Doc | Kod | OWNER-only | Query parametreleri | Uyum |
|-----|-----|-----------|---------------------|------|
| `GET /reports/venues` | ✓ | requireOwner ✓ | yok | ✅ |
| `GET /reports/overview` | ✓ | ✓ | tümü | ⚠️ dead code |
| `GET /reports/revenue-series` | ✓ | ✓ | `compare` parametresi accepted ama kullanılmıyor | ⚠️ |
| `GET /reports/tables` | ✓ | ✓ | tümü | ⚠️ N+1 |
| `GET /reports/tables/{id}` | ✓ | ✓ | tümü | ⚠️ venueId mismatch'i kontrol etmiyor |
| `GET /reports/managers` | ✓ | ✓ | tümü, period kullanılmıyor | ⚠️ username = email |
| `GET /reports/managers/{id}` | ✓ | ✓ | tümü | 🛑 **NPE bug** |
| `GET /reports/forecast` | ✓ | ✓ | tümü | ⚠️ YEAR threshold yanlış |

---

## P0 — Kritik

### 1. NullPointerException: `Long.valueOf(Integer)` autounbox

[ReportsServiceImpl.mapToSessionLogEntry:572-587](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L572-L587):

```java
.durationSeconds(Long.valueOf(s.getDurationSeconds()))
.totalAmount(s.getTotalAmount())
```

`Session.durationSeconds` `Integer` ve **CANCELLED session'lar için null**. `Long.valueOf(Integer)` overload'ı yok → `Long.valueOf(long)` çağrılıyor + autounbox → NPE.

**Reproduce:** Manager bir session cancel etti, owner `/reports/managers/{id}` çağırıyor → endpoint 500.

Düzeltme:
```java
.durationSeconds(s.getDurationSeconds() != null ? s.getDurationSeconds().longValue() : null)
```

DTO field tipi `Long` (doc'la uyumlu) zaten `null` kabul ediyor.

### 2. `resolveManager` cross-owner leak

[ReportsServiceImpl.resolveManager:401-405](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L401-L405):

```java
private User resolveManager(String managerId) {
    return reportsRepository.findManagerById(managerId)
            .orElseThrow(() -> new AppException("MANAGER_NOT_FOUND", ...));
}
```

`findManagerById` global lookup — owner filtresi yok. Bir owner başka owner'ın manager ID'sini geçerse:

1. `findManagerById` user'ı bulur → OK.
2. `sumRevenueByManager(venue, manager, ...)` çağrısında `venue` requesting owner'ın, `manager` başka owner'ın → revenue=0, sessions=0.
3. Manager'ın `name`, `email` (username) yine de döner.

Sonuç: **Owner A, owner B'nin manager isim/email'lerini öğrenebilir** sadece ID'yi tahmin ederek. Düzeltme:

```java
return userRepository.findByIdAndOwner(parseUuid(managerId), currentOwner)
        .orElseThrow(...);
```

(`User.owner` field'ı eklendikten sonra. Bkz [01-auth-review.md](01-auth-review.md) #8.)

### 3. `getTableDetail` venue mismatch'i atlanıyor

[ReportsServiceImpl.getTableDetail:177-215](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L177-L215):

```java
Venue venue = resolveVenue(user, venueId);
Tables table = resolveTable(user, tableId);
```

`resolveTable` table'ın owner'a ait olduğunu kontrol eder ama **venue eşleşmesini değil**. Owner `venueId=V1, tableId=T2` (T2 başka venue'da) geçerse iki ayrı entity dönecek; summary `venueName=V1` ama heatmap T2'ye ait — inconsistent. Düzeltme:

```java
if (!table.getVenue().getId().equals(venue.getId())) {
    throw new AppException("TABLE_NOT_FOUND", HttpStatus.NOT_FOUND);
}
```

---

## P1 — Yüksek

### 4. `getOverview` dead code + tutarsız field set

[ReportsServiceImpl.getOverview:68-83](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L68-L83):

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

`current` KpiBlock üretiliyor sonra hiç kullanılmıyor — dead code, sil.

### 5. `getRevenueSeries` `compare` parametresi unused

[ReportsController:55-63](../src/main/java/kg/sportmanager/controller/ReportsController.java#L55-L63) `boolean compare` alıyor, service'e geçmiyor:

```java
return ResponseEntity.ok(reportsService.getRevenueSeries(user, period, from, to, venueId));
```

`compare` flag'inin revenue-series üzerinde anlamlı kullanımı yok — doc da göstermiyor. Controller'dan param'ı kaldır veya service'e ekle (no-op).

### 6. `getManagers` `period` parametresi unused

[ReportsServiceImpl.getManagers:221-242](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L221-L242) — `period` parametre alıyor ama kullanmıyor. `from`/`to` ile aggregation yapıyor (doğru). `period` ve `compare` parametreleri controller'dan geliyor ama service ignore ediyor. Doc kontratı `previous` block için `period` lazım değil çünkü managers endpoint'i `previous` döndürmüyor. Method imzasını temizle.

### 7. `managerStats` projeksiyonu `username = email` map'liyor

[ReportsRepository.managerStats:156-170](../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L156-L170):

```java
s.manager.email AS username,
```

Doc [managers-api.md](../docs/managers-api.md#modelsalan-referansı):

```ts
username: string  // '@' olmadan; UI'da '@username' olarak gösterilir
```

`email` ≠ `username`. `@aibek` UI'da `@john@example.com` olarak görünür → çirkin. Backend'de `username` adında ayrı alan **yok** — `User` entity'de `username` field'ı tanımlanmamış. **Schema gap.**

Düzeltme:
1. `User` entity'sine `@Column(unique=true) String username;`
2. Register sırasında doldur (kullanıcıdan al veya email local part'ından üret).
3. Projection'u `s.manager.username AS username` olarak değiştir.

### 8. `findManagerById` JPQL cast sorgusu performans + portability

[ReportsRepository:209-210](../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L209-L210):

```java
@Query("SELECT u FROM User u WHERE CAST(u.id AS string) = :id")
Optional<User> findManagerById(@Param("id") String id);
```

Tüm `users` tablosunu tarar (index kullanılamaz, cast var). `UUID.fromString(id)` parse edip `findById` kullanmak yeterli ve idiomatic. Düzeltme: method'u sil, `userRepository.findById(UUID.fromString(managerId))` çağır.

### 9. `getTables` N+1 sorgu

[ReportsServiceImpl.getTables:146-165](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L146-L165):

```java
for (Tables t : tables) {
    long rev = reportsRepository.sumRevenueByTable(t, from, to);
    long sess = reportsRepository.countCompletedByTable(t, from, to);
    ...
}
```

50 masa → 100 sorgu. **Mevcut `revenueByTable` (GROUP BY) sorgusu zaten var** ([line 90-98](../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L90-L98))! Aynı pattern ile sessions sayısı için `countByTable` ekle ve aggregate yap:

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

Ardından memory'de masa listesi ile join et.

### 10. `buildRevenueSeries` bucket başına 2 sorgu (N gün × 2)

[ReportsServiceImpl.buildRevenueSeries:430-443](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L430-L443):

```java
return buckets.stream().map(bucket -> {
    Instant bucketEnd = nextBucket(period, bucket);
    long rev = reportsRepository.sumRevenue(venue, bucket, bucketEnd);
    long sess = reportsRepository.countCompleted(venue, bucket, bucketEnd);
    ...
}).toList();
```

30 günlük MONTH = 60 sorgu. SQL GROUP BY day ile tek sorguda çözülür:

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

(Postgres `date_trunc` JPQL'de FUNCTION ile çağrılabilir. YEAR için `'month'` dilimi.)

### 11. `forecast` YEAR period'da `< 7` threshold'u

[ReportsServiceImpl.getForecast:298-300](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L298-L300):

```java
if (actualSeries.size() < 7) {
    throw new AppException("NOT_ENOUGH_DATA", ...);
}
```

YEAR period'da bucket=ay. Mayıs ortasında çağrıldığında `actualSeries.size() = 5` (Jan-May) → `NOT_ENOUGH_DATA`. Doc forecast'ın YEAR'da çalışmasını öngörüyor (`previousPeriodTotal = full previous year`). Bu kuralı YEAR için sıfır olmamalı ama threshold farklı olmalı (örn. >= 2 ay).

Düzeltme: period-aware threshold:

```java
int minBuckets = "YEAR".equalsIgnoreCase(period) ? 2 : 7;
if (actualSeries.size() < minBuckets) throw ...;
```

### 12. `clippedPrevious` YEAR period için DAY-bazlı hesaplama

[ReportsServiceImpl.clippedPrevious:488-501](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L488-L501):

```java
long elapsedDays = ChronoUnit.DAYS.between(from, to);
Instant prevFrom = ... minusYears(1);
Instant prevTo = prevFrom.plus(elapsedDays, ChronoUnit.DAYS);
```

YEAR period için OK aslında: "geçen yıl aynı tarih aralığı". Ama bucket-size'a dikkat: clipped previous'tan revenue ve series fetch ederken **clipped previous için de aylık bucket kullanılmalı**. Şu an series fetch'inde period geliyor → OK. Sorun yok ama net değil — yorumla.

### 13. `currency` resolution masa başına farklılaşabilir

[ReportsServiceImpl.resolveCurrency:407-414](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L407-L414):

```java
return tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue)
        .stream().findFirst()
        .map(t -> t.getCurrency().name())
        .orElse("KGS");
```

Doc: "MVP tek mekan kapsamında çalıştığı için tek currency yeterli". Ama masalar farklı currency olabilir (constraint yok). İlk masanın currency'sini tüm reporting'e uygular. Owner farklı currency'li masalara sahip olursa rapor yanlış toplam gösterir.

Düzeltme: ya venue'ye currency field'ı ekle ve tüm masaları aynı currency'ye zorla, ya da reports'ta currency'leri ayrı toplam göster.

---

## P2 — Orta

### 14. `revenueByTableRaw` UUID cast cast'i runtime'da kırılabilir

[ReportsRepository.revenueByTableRaw:99-101](../src/main/java/kg/sportmanager/repository/ReportsRepository.java#L99-L101):

```java
revenueByTableRaw(venue, from, to).forEach(row -> result.put((UUID) row[0], (Long) row[1]));
```

Hibernate `SUM(s.totalAmount)` `BigDecimal` veya `Long` döndürebilir, JDBC driver'a göre. `(Long) row[1]` ClassCastException atabilir. Güvenli:

```java
result.put((UUID) row[0], ((Number) row[1]).longValue());
```

Aynı pattern başka projection'larda gerekebilir.

### 15. Heatmap UTC, doc'ta venue local time TBD

Doc [reports-api.md §5](../docs/reports-api.md#5-get-apiv1reportstablesid):
> Hour ordering: ... (yerel saatte; venue zone gerekirse v2'de).

Kod UTC kullanıyor. Bişkek için UTC+6 → akşam 18:00 saatleri öğlen 12:00 olarak görünür. **Owner yanlış peak hours çıkarır.** v2'de `Venue.timezone` ekle.

### 16. `SessionLogEntryResponse.tableNumber` `int` primitive

[SessionLogEntryResponse:18](../src/main/java/kg/sportmanager/dto/response/reports/SessionLogEntryResponse.java#L18):

```java
private int tableNumber;
```

Tables.number `Integer` (boxed). DTO field primitive. Auto-unbox null değeri yakalamaz → NPE riski. `Integer` yap.

### 17. `OverviewResponse.KpiBlock.previous` her zaman null ama field var

Recursive yapı önlemek için her zaman null set ediliyor. OK ama JSON output'unda `"previous": null` görünür → mobil parse OK. Doc:
```json
"previous": {
  ...,
  "previous": null
}
```
Doc bunu açıkça gösteriyor. ✅

### 18. `OverviewResponse` `@JsonInclude(ALWAYS)` ile null'lar atılmıyor

Doc: `compare=false` → `previous: null` (field var). `@JsonInclude(ALWAYS)` doğru. ✅

### 19. Forecast `linearRegression` zero-denom case

[ReportsServiceImpl.linearRegression:556-570](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L556-L570):

```java
if (denom == 0) return new double[]{0, sumY / n};
```

OK — tüm x değerleri aynı olduğunda fallback. ✅

### 20. Forecast `previousPeriodTotal` MONTH için tam Nisan değeri doğru mu?

`fullPreviousPeriod("MONTH", fromMay)` → first day of April → first day of May. Doğru tam Nisan range'i. ✅

### 21. `actualBuckets.contains(bucket)` her bucket için stream içinde linear search

[ReportsServiceImpl.getForecast:330-336](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L330-L336):

```java
if (actualBuckets.contains(bucket)) {
    long factRev = actualSeries.stream()
        .filter(p -> p.getBucket().equals(bucket))
        .mapToLong(...).findFirst().orElse(0L);
```

`actualSeries`'i `Map<Instant, RevenuePoint>` olarak indeksle, lookup O(1) olsun.

---

## P3 — Düşük

- `record PeriodRange` service içinde nested — OK.
- `@DateTimeFormat(iso=ISO.DATE_TIME)` controller param'larında — Spring otomatik parse'ler. ✅
- `RequestParam(defaultValue="true") boolean compare` controller'da OK.
- `requireOwner` her endpoint'in başında tekrarlanıyor — `@PreAuthorize("hasRole('OWNER')")` annotation ile method-level security açılarak temizlenir.
- Yorumlar Rusça, kod İngilizce. Tutarlı.

---

## Eylem Önerileri (Sırasız)

1. **Long.valueOf null bug'ını düzelt** — endpoint çökmeyi engelle.
2. **`resolveManager`'a owner filtresi ekle** — `User.owner` schema ekle.
3. **`getTableDetail` venue-table tutarlılığı** — cross-check.
4. **N+1 sorguları aggregate'le** — `getTables`, `buildRevenueSeries`, `buildRevenueSeriesByTable`.
5. **Dead code temizliği** — `getOverview` `current` block, unused `compare`/`period` parametreler.
6. **`username` schema gap'ini doldur** — User entity'ye field ekle, projection'u güncelle.
7. **`findManagerById` CAST sorgusunu kaldır** — `UUID.fromString` + `findById`.
8. **Forecast threshold period-aware**.
9. **Heatmap zone'u** — v2'de `Venue.timezone` ile yerelleştir.
10. **`((Number) row[1]).longValue()`** ile cast'ları güvenli yap.
