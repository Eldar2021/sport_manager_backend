# Session Review

Doc kaynağı: [docs/session_api.md](../docs/session_api.md)  
Kod: [SessionController](../src/main/java/kg/sportmanager/controller/SessionController.java), [SessionServiceImpl](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java), [Session entity](../src/main/java/kg/sportmanager/entity/Session.java), [SessionMapper](../src/main/java/kg/sportmanager/util/SessionMapper.java), [SessionRepository](../src/main/java/kg/sportmanager/repository/SessionRepository.java)

---

## Endpoint Uyum Matrisi

| Doc | Kod | Auth Doc | Kod | Response | Uyum |
|-----|-----|----------|-----|----------|------|
| `POST /api/v1/session/start` | aynı | Both | `validateTableAccess` ⚠️ | SessionLite | ⚠️ |
| `POST /api/v1/session/{id}/pause` | aynı | Both | ⚠️ + idempotency bug | SessionLite | ⚠️ |
| `POST /api/v1/session/{id}/resume` | aynı | Both | ⚠️ | SessionLite | ⚠️ |
| `POST /api/v1/session/{id}/finish` | aynı | Both | ⚠️ | SessionResult | ⚠️ |
| `POST /api/v1/session/{id}/cancel` | aynı | Both (60s gate) | ✅ window | SessionResult | ⚠️ |

`managerId` alanı DTO'larda eksik (aşağıda).

---

## P0 — Kritik Güvenlik

### 1. Manager'lar başka owner'ların session'larını manipüle edebilir

[SessionServiceImpl.validateTableAccess:240-252](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252):

```java
boolean hasAccess = switch (user.getRole()) {
    case OWNER -> owner.getId().equals(user.getId());
    case MANAGER -> true;   // ← BUG
};
```

Yorumda da yazıyor: "Manager için doğrulama doğru schema ile yapılmalı, şu an her zaman true". Sonuç:

- Bir manager `POST /session/start { tableId: <başka owner'ın masası> }` çağırırsa → 200, session başlar.
- Bir manager session ID'sini başkasından (örn. log) öğrenirse → `pause`, `resume`, `finish`, `cancel` çağırabilir.
- `finish` ile başkasının kazancını sıfırlayabilir (discount=100).

**Cross-tenant veri sızıntısı + sabotaj.** Production'a girmemeli.

**Düzeltme** (manager↔owner ilişkisi düzeldikten sonra):

```java
private void validateTableAccess(User user, Tables table) {
    User tableOwner = table.getVenue().getOwner();
    User userOwner = user.getRole() == User.Role.OWNER ? user : user.getOwner();
    if (userOwner == null || !tableOwner.getId().equals(userOwner.getId())) {
        throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
    }
}
```

### 2. Session start race condition

[SessionServiceImpl.start:51-69](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L51-L69):

```java
if (sessionRepository.existsByTableAndIsActiveTrue(table)) {
    throw new AppException("TABLE_HAS_ACTIVE_SESSION", ...);
}
Instant now = Instant.now();
Session session = Session.builder()...build();
sessionRepository.save(session);
```

Check-then-act. Default isolation READ_COMMITTED. İki paralel `/start` aynı masaya:

1. T1 exists check → false
2. T2 exists check → false (T1 commit etmedi henüz)
3. T1 insert
4. T2 insert
5. İki ACTIVE session aynı masada → tüm session lifecycle bozulur (pause hangisini paused yapacak?)

Docs [session_api.md §1 Race condition](../docs/session_api.md#1-start-session):
> Backend transaction içinde masayı lock'lar. İki paralel start denemesi gelirse biri başarılı olur, diğeri 409 alır.

**Düzeltme A** — pessimistic lock:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Tables t WHERE t.id = :id AND t.deletedAt IS NULL")
Optional<Tables> findByIdForUpdate(@Param("id") UUID id);
```

**Düzeltme B** — DB partial unique constraint:
```sql
CREATE UNIQUE INDEX one_active_session_per_table ON sessions (table_id) WHERE is_active = true;
```

Düzeltme B daha basit ve daha güvenli — DB tarafında garanti.

### 3. Pause idempotency bug — `pausedAt` üzerine yazılır

[SessionServiceImpl.pause:79-100](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L79-L100):

```java
if (session.getStatus() != Session.SessionStatus.ACTIVE) {
    throw new AppException("SESSION_NOT_ACTIVE", ...);
}
session.setPaused(true);
session.setPausedAt(now);
```

`status` enum'da `PAUSED` yok — sadece `ACTIVE/COMPLETED/CANCELLED`. Pause yaparken `status` ACTIVE kalıyor, `isPaused=true` flag set ediliyor. **İkinci pause çağrısı:**

1. `findActiveSession` → isActive=true ✓
2. `status != ACTIVE` → false (hala ACTIVE)
3. SESSION_NOT_ACTIVE atılmıyor
4. `setPausedAt(now)` → **önceki pausedAt overwrite**
5. Önceki `pausedAt - now` arası süre kaybedilir → ücretsiz molla

Kullanıcı (veya bozuk istemci) iki pause atarsa sayaç hatalı. Düzeltme: ilk önce `isPaused` flag'i kontrol et:

```java
if (session.isPaused()) {
    throw new AppException("SESSION_ALREADY_PAUSED", HttpStatus.CONFLICT);
}
```

`SESSION_ALREADY_PAUSED` veya `SESSION_NOT_ACTIVE` semantik kararı kalır.

### 4. `cancel`'de "already cancelled" yanlış hata kodu

[SessionServiceImpl.findActiveSession:224-233](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L224-L233):

```java
if (!session.isActive()) {
    throw new AppException("SESSION_ALREADY_COMPLETED", HttpStatus.CONFLICT);
}
```

Zaten cancelled olan session için de aynı hata atılır — kullanıcı "tamamlanmış" mesajı görür, gerçekte ise iptal edilmiş. UX yanlış. Status'a göre ayır:

```java
if (session.getStatus() == COMPLETED) throw new AppException("SESSION_ALREADY_COMPLETED", ...);
if (session.getStatus() == CANCELLED) throw new AppException("SESSION_ALREADY_CANCELLED", ...);
```

(Yeni hata kodunu `MESSAGES` map'ine ekle.)

---

## P1 — Yüksek

### 5. DTO'larda `managerId` eksik

Doc'taki `SessionLite` ve `SessionResult`:

```ts
{
  id: ...,
  tableId: ...,
  managerId: string (uuid),  // ← session'ı başlatan
  ...
}
```

[SessionLiteResponse](../src/main/java/kg/sportmanager/dto/response/SessionLiteResponse.java) ve [SessionResultResponse](../src/main/java/kg/sportmanager/dto/response/SessionResultResponse.java)'da `managerId` field'ı **yok**. Entity'de `manager` field'ı set ediliyor ([SessionServiceImpl.start:65](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L65)) ama response'a dahil edilmiyor.

Sonuç: Reports/Manager performans ekranı için kritik — istemci hangi manager'ın hangi session'ı başlattığını göremiyor (reports listesi haricinde).

Düzeltme: her iki DTO'ya `UUID managerId` ekle, mapper'da doldur.

### 6. `SessionResponse` (Home'daki masa kartı) `managerId` ve `status` taşımıyor

[SessionResponse](../src/main/java/kg/sportmanager/dto/response/SessionResponse.java) Home'daki masa kartında dönen ayrı bir DTO. Field'ları:

```java
private boolean isActive;
private boolean isPaused;
// ama status string yok, managerId yok
```

Doc [home_page_api.md § Session](../docs/home_page_api.md#session) `managerId` zorunlu. **HomeMapper.toSessionResponse'ı düzelt + `SessionResponse`'a `managerId` ekle.**

İdeal çözüm: `SessionResponse`'ı kaldır, `SessionLiteResponse`'ı kullan. İki ayrı DTO yapısı (string-tarih vs Instant-tarih) gereksiz.

### 7. Cancel reason 1-200 char ama lower-bound 1 elle kontrol

[SessionServiceImpl.cancel:190-193](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L190-L193):

```java
if (request.getReason() == null || request.getReason().isBlank()
        || request.getReason().length() > 200) {
    throw new AppException("VALIDATION_ERROR", ...);
}
```

OK ama Jakarta `@NotBlank @Size(max=200)` ile yapılması daha temiz. Mevcut çözüm geçerli.

### 8. `finish`'te `subtotal` Integer overflow riski

[SessionServiceImpl.finish:160](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L160):

```java
int subtotal = calculateSubtotal(billableSeconds, session.getTarifAmountSnapshot(), session.getTarifTypeSnapshot());
```

`calculateSubtotal` `int` döner. Worst case: 1 yıllık session (86400×365 ≈ 31.5M sec) × tarifAmount 1M / 60 (DAY) = ~31.5M × 1M / 86400 ≈ 365M. Integer max 2.1B, OK. Ama `tarifAmount=1M` + `DAY` tarif + 100 yıl gibi extrem case yakalar. Pratik değil ama defansif olarak `long` daha güvenli.

Total amount entity'de zaten `Long`, OK.

### 9. `finish` floats yuvarlama tutarsız

[SessionServiceImpl.finish:172](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L172):

```java
int discountAmount = Math.round(subtotal * discountPercent / 100f);
```

[SessionMapper.toResult:36](../src/main/java/kg/sportmanager/util/SessionMapper.java#L36):

```java
int discountAmount = Math.round(subtotal * discountPercent / 100f);
```

Aynı hesap iki yerde tekrarlanıyor. Mapper hesabı ikinci kez yapıyor, biri service'ten subtotal+discount alıyor. **Hesabı bir yerde tut** — service yapsın, mapper yalnız field'ları kopyalasın.

### 10. Pause süresi `getEpochSecond()` ile millisaniye kaybı

[SessionServiceImpl.resume:120](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L120):

```java
long pausedDuration = now.getEpochSecond() - session.getPausedAt().getEpochSecond();
```

Doc "saniye cinsinden" diyor → OK pragmatik. Ama 0.6s pause 1s sayılır mı 0s mı? Truncation → 0s. Toplam paused süresi bir kaç saniye az çıkabilir. Müşteri lehine hata ama owner için risk. Pratikte ihmal edilebilir.

### 11. `finish`'te pause cleanup

[SessionServiceImpl.finish:148-153](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L148-L153):

```java
int totalPausedSeconds = session.getTotalPausedSeconds();
if (session.isPaused() && session.getPausedAt() != null) {
    long pausedDuration = now.getEpochSecond() - session.getPausedAt().getEpochSecond();
    totalPausedSeconds += (int) pausedDuration;
}
```

Doc: "Session PAUSED durumdaysa otomatik resume edilir, sonra finish." Code matematiği doğru yapıyor (paused süresi totalPausedSeconds'a eklenir) ama `setResumedAt(now)` set etmiyor — sadece `setPaused(false)`. Audit için `resumedAt` set edilmesi gerek. Pratikte zarar yok.

---

## P2 — Orta

### 12. Doc'taki `endedAt` field'ı `cancel`'de yanlış vakitte set ediliyor mu?

[SessionServiceImpl.cancel:207](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L207): `session.setEndedAt(now)` ✓. Doc örneği:
```json
"startedAt": "2026-04-27T18:42:00.000Z",
"endedAt": "2026-04-27T18:42:30.000Z"
```
OK. ✅

### 13. `pause`'da `setStatus(ACTIVE)` lüzumsuz

[SessionServiceImpl.pause:92](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L92): `session.setStatus(Session.SessionStatus.ACTIVE)` — zaten ACTIVE'di. Dead code. Sil.

### 14. `resume`'da `setResumedAt(now)` her resume için overwrite

İlk resume `resumedAt=T1`, ikinci resume `resumedAt=T2` → audit kaybı. Multi-pause/resume cycle'da hangi resume olduğu belli olmaz. Doc bu alanı response'a göstermiyor, sadece state. OK ama pause history tutmak için ayrı tablo gerek (doc önerisi: pause history backend'de tutulur). Bu MVP'de scope dışı.

### 15. `SessionLiteResponse` `tarifTypeSnapshot` enum yerine string istemiyor

[SessionLiteResponse.java:30](../src/main/java/kg/sportmanager/dto/response/SessionLiteResponse.java#L30):

```java
private Tables.TarifType tarifTypeSnapshot;
```

Jackson default enum'u `"HOUR"` string'i olarak serialize eder. Doc `"HOUR" | "MINUTE" | "DAY"` → OK. ✅

### 16. `SessionMapper.toResult` `discountPercent` COMPLETED yoksa null koyma mantığı

[SessionMapper:48](../src/main/java/kg/sportmanager/util/SessionMapper.java#L48):

```java
.discountPercent(s.getStatus() == Session.SessionStatus.COMPLETED ? discountPercent : null)
```

OK. CANCELLED için null döner — doc ile uyumlu. ✅

### 17. `finish` zaten cancelled session'a girerse hatalı hesap

`findActiveSession` `isActive=false` ise reddediyor. CANCELLED status'lu session `isActive=false`. OK ekstra koruma var. ✅

### 18. `endedAt` ile `startedAt` arasında negatif fark olabilir mi?

`now` server zamanı ≥ `startedAt` (server kendisi yazdığı için). Edge: NTP düzeltmesi geriye gidebilir. `Math.max(0, ...)` zaten konmuş (line 157). ✅

---

## P3 — Düşük

- `CANCEL_WINDOW_SECONDS = 60L` static final OK.
- `SessionRepository.findCompletedByTableAndRange` ve `ReportsRepository.findCompletedByTableAndRange` aynı sorgu **iki kere tanımlı**. Repository inheritance veya ortak interface ile birleştir.
- `SessionRepository.findSessionLogByManager` LIMIT 40 hardcoded — ReportsRepository'deki paralel sorgu parametrik. Çakışma. ReportsRepository'dekini kullan, bunu sil.
- `SessionServiceImpl.parseUuid` `VALIDATION_ERROR` döner — path param için 400 daha uygun ama OK.
- `SessionStatus.ACTIVE` enum + `isActive` boolean — redundant. `isActive=true ↔ status in (ACTIVE)`. Tek alana indir.
- Code yorumları Rusça — diğer kodlarla tutarlı.

---

## Eylem Önerileri

1. **Cross-tenant manager izolasyonu** — `User.owner` ekle + `validateTableAccess` düzelt.
2. **DB level: tek aktif session constraint** — partial unique index.
3. **`pause` idempotency** — `isPaused` kontrolü ekle.
4. **`managerId` field'ını her session response DTO'sunda** — SessionLite, SessionResult, SessionResponse.
5. **Cancelled vs Completed** ayrı hata kodları.
6. **`SessionResponse` ve `SessionLiteResponse` birleştir** — Home'daki masa kartı SessionLite kullansın.
7. **Discount hesabını tek yere koy** — service veya mapper, ikisi de değil.
8. **Pause history için ayrı tablo** (opsiyonel, v2).
