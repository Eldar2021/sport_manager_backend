# Genel Review Özeti

Tarih: 2026-05-14  
Reviewer: Claude (kod / docs karşılaştırması)  
Scope: `develop` branch'inin mevcut durumu vs `docs/*.md` sözleşmeleri

---

## Skor

| Alan               | Puan | Yorum                                                                                          |
| ------------------ | ---- | ---------------------------------------------------------------------------------------------- |
| Auth               | 5/10 | Endpoint'ler var ama tutarsız hata zarfı, `/api/v1` prefix yok, forgot-password sahte          |
| Home (Venue/Table) | 6/10 | Çoğunlukla çalışıyor; manager→owner ilişkisi tamamen kırık                                     |
| Session            | 5/10 | Doğru iş mantığı ama **2 kritik güvenlik açığı**: manager cross-tenant erişim + race condition |
| Reports            | 4/10 | Endpoint'ler var ama **NPE bug'ı**, N+1 sorgular, cross-owner manager leak                     |
| Managers API       | 0/10 | **Hiç implement edilmemiş**                                                                    |
| Subscription API   | 0/10 | **Hiç implement edilmemiş**, gate hiçbir yerde uygulanmıyor                                    |
| Security & i18n    | 4/10 | JWT çalışıyor; secret commit'lenmiş, parolalar log'a yazılıyor, çeviri çoğu hata kodu için yok |
| Config & Build     | 3/10 | Hardcoded secret, env-var yok, Flyway yok, **hiç test yok**, ddl-auto=update                   |

**Toplam:** Bu kod junior-dev seviyesinde, "mutlu yol"da çalışan ama production'a hazır olmayan bir backend. **Mobil istemci docs'tan implementasyona geçmeye çalışırsa kırılır** (path prefix uyumsuzluğu + 2 eksik API + hata zarfı tutarsızlığı).

---

## En Kritik 10 Bulgu (öncelik sırasıyla)

### P0 — Production'a girmeden çözülmeli

1. **Cross-tenant session manipülasyonu** — [SessionServiceImpl.validateTableAccess](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252) MANAGER için `case MANAGER -> true` döner. Herhangi bir manager, sistemdeki **her session'ı** pause/resume/finish/cancel edebilir (sessionId tahmin etmesi gerekiyor ama yine de yetki kontrolü yok). Aynı şekilde başka owner'ın masasında session başlatabilir. **Multi-tenant veri sızıntısı.**

2. **Subscription API ve gate tamamen eksik** — [docs/subscription-api.md](../docs/subscription-api.md) hem 5 endpoint hem de "EXPIRED owner → 403 SUBSCRIPTION_REQUIRED" gate kuralı tanımlıyor. Backend'de ne controller/service ne de entity (`Subscription`, `Payment`) var. Ödeme akışı çalışmıyor, owner'ı kısıtlama mekanizması yok → ücretsiz çalışan SaaS.

3. **Managers API tamamen eksik** — `GET /api/v1/managers`, `DELETE /api/v1/managers/{id}` yok. Mobil uygulamada manager listesi/silme ekranı kırık.

4. **`ManagerDetailResponse` NPE** — [ReportsServiceImpl.mapToSessionLogEntry](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L583): `Long.valueOf(s.getDurationSeconds())` — `durationSeconds` CANCELLED session'larda null. Manager detay sayfasında iptal edilmiş session varsa endpoint 500 atar. (`Long.valueOf` Integer alır + autounbox → NPE.)

5. **Manager↔Owner ilişkisi schema'da yok** — `User` entity'de `owner` alanı yok. `InviteCode.owner` ile davet eden bilinse de register sırasında manager User kaydına atanmıyor. Sonuç: Manager hangi owner'a bağlı belli değil. [HomeServiceImpl.resolveOwner](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L248-L252) bu yüzden manager için kendisini döner ve manager **boş venue listesi görür**. Tüm manager UX'i kırık.

### P1 — Yakında çözülmeli

6. **Path prefix uyumsuzluğu** — Docs `/api/v1/auth/**` diyor; [AuthController](../src/main/java/kg/sportmanager/controller/AuthController.java#L25) `/auth/**` kullanıyor. Mobil istemci docs'tan implementasyon yaparsa 404 alır.

7. **Hata zarfı iki ayrı format** — `AuthServiceImpl` `ResponseStatusException(status, "ERROR_CODE")` atıyor → Spring default body (`{"status":401,"error":"Unauthorized","message":"INVALID_CREDENTIALS"}`). Geri kalan servisler `AppException` + GlobalExceptionHandler ile multilingual `{code, message:{en,ru,ky}}` döner. Tek tip body bekleyen istemci auth hatalarını parse edemez.

8. **`MESSAGES` map'i eksik** — [GlobalExceptionHandler](../src/main/java/kg/sportmanager/exception/GlobalExceptionHandler.java#L13-L54) yalnız 8 kod tanımlı. Eksikler: `SESSION_NOT_FOUND`, `SESSION_NOT_ACTIVE`, `SESSION_NOT_PAUSED`, `SESSION_ALREADY_COMPLETED`, `CANCEL_WINDOW_EXPIRED`, `INVALID_DISCOUNT`, `MANAGER_NOT_FOUND`, `NOT_ENOUGH_DATA`, `INVALID_INVITE_CODE`, `INVALID_CREDENTIALS`, `EMAIL_ALREADY_USED`, `PHONE_ALREADY_USED`, `ACCOUNT_LOCKED`, `BAD_REQUEST`, `SUBSCRIPTION_REQUIRED`, `PAYMENT_NOT_FOUND` vs. Map'te yoksa fallback olarak kodu mesaj metni yapar → kullanıcı "SESSION_NOT_FOUND" mesajını görür.

9. **Session start race condition** — [SessionServiceImpl.start](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L51-L69): `existsByTableAndIsActiveTrue` → insert. Pessimistic lock yok. İki paralel start aynı masada iki ACTIVE session yaratabilir. Docs (`session_api.md`): "Backend transaction içinde masayı lock'lar".

10. **Şifre log'a yazılıyor** — [AuthController.login:37](../src/main/java/kg/sportmanager/controller/AuthController.java#L37) `log.info("Login request: {}", request)` → `LoginRequest.toString()` (Lombok @Data) parolayı düz metin olarak log'a basar. GDPR + iç güvenlik ihlali.

---

## Cross-Cutting (her domain'i etkileyen) Sorunlar

### Doc–Code Uyumsuzlukları

| Konu                                           | Doc                                 | Kod                                      | Etki                                             |
| ---------------------------------------------- | ----------------------------------- | ---------------------------------------- | ------------------------------------------------ |
| Auth path prefix                               | `/api/v1/auth/**`                   | `/auth/**`                               | Mobil 404                                        |
| Refresh response                               | Sadece tokenlar                     | `{user, accessToken, refreshToken}`      | Doc ihlali; kritik değil                         |
| `details` field error body                     | `null` veya array                   | Tamamen yok (ErrorResponse'da field yok) | Validation hataları parse edilemez               |
| Validation `details: [{field, rule, message}]` | Var                                 | Yok — hep `VALIDATION_ERROR`             | Mobil hangi alan yanlış göremiyor                |
| `Accept-Language` davranışı                    | Yorum: header set'liyse tek dil dön | Her zaman 3 dil                          | Doc'ta opsiyonel — kritik değil                  |
| Currency `KGS\|USD\|RUB\|KZT\|TRY`             | 5 değer                             | Aynı 5 değer enum'da                     | OK                                               |
| `tarifAmountSnapshot` integer                  | integer                             | `Integer`                                | OK                                               |
| Session `managerId`                            | uuid                                | `UUID` (entity'de var)                   | OK ama response DTO'larında alan eksik (bkz. 03) |

### Validation Eksikliği

Hiçbir DTO'da Jakarta validation annotation'ı yok (`@NotBlank`, `@Size`, `@Min`, `@Email`, `@Pattern`). Controller'larda `@Valid` yok. Tüm validation servis katmanında elle yapılıyor ve **doc'ta belirtilen `details` array'i ile birlikte hata gönderemiyor.** İki seçenek:

- A) Jakarta Validation + `@RestControllerAdvice` `MethodArgumentNotValidException` handler ekle, `details` field'ını doldur.
- B) Mevcut elle validation'da `AppException`'ı `details` listesi taşıyacak şekilde genişlet.

### N+1 Sorgular

- [HomeServiceImpl.getVenueList](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L37-L45): venue başına bir `findByVenueAndDeletedAtIsNullOrderByNumberAsc` (tüm satırları yüklüyor sadece sayma için). 10 venue × 50 masa = 500 row gereksiz. **`countByVenueAndDeletedAtIsNull` repo metodu ekle.**
- [ReportsServiceImpl.getTables](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L146-L165): masa başına 2 sorgu (revenue + count). N masa = 2N sorgu. `getOverview`'daki gibi tek `GROUP BY` ile çek.
- [HomeServiceImpl.buildSelectedVenueResponse](../src/main/java/kg/sportmanager/service/impl/HomeServiceImpl.java#L227-L238): masa başına `findByTableAndIsActiveTrue`. Tek bir `findActiveByTableIn(...)` ile çözülür.

### Eksik Cross-Cutting Özellikler

- **CORS config yok** → mobil farklı origin'den çağırırsa preflight 401 alır
- **Rate limiting yok** → docs (home_page_api.md, reports-api.md) endpoint başı dakika limitleri belirtmiş
- **Audit log yok** — kim ne zaman session başlattı/iptal etti DB'de var ama "owner için" görünür değil
- **Tests yok** — `test` scope dependency'leri pom.xml'de var ama `src/test/` boş
- **Health endpoint yok** — Spring Boot Actuator dahil değil
- **Caching yok** — docs reports için 5dk server-side cache öneriyor; uygulanmamış

---

## Kod Kalitesi Notları

- **Yorum dili karışık** — Rusça + Türkçe + İngilizce karışık. Tek dile karar verilmeli (proje açıklaması Rusça olduğundan Rusça mantıklı, ama docs Türkçe).
- **DTO içinde tarih tipleri tutarsız** — bazı DTO'larda `String createdAt` (`.toString()` ile), bazılarında `Instant` (Jackson serialization). [HomeMapper](../src/main/java/kg/sportmanager/mapper/HomeMapper.java) `toString()` kullanıyor → "2026-04-15T10:30:00Z" gibi millisaniyesiz format döner. Doc örnekleri `.000Z` ile bitiyor → Jackson `Instant`'a bırak.
- **`CommonMapper.java` boş** — `@UtilityClass` ile sınıf olmuş, hiç method yok. Sil.
- **`SessionResponse` vs `SessionLiteResponse` ikiliği** — HomeMapper "SessionResponse" üretiyor (string tarihli), SessionMapper "SessionLiteResponse" (Instant'lı). İki ayrı DTO aynı modeli temsil ediyor. Birleştir.
- **`CommonMapper` ve `SessionMapper` `util/` paketinde, `HomeMapper` `mapper/` paketinde** — tutarsız konum.
- **`@JsonIgnore` eksik** — `User` entity password ve refreshToken alanlarını ifşa edebilir; `@AuthenticationPrincipal User` controller'lardan tek doğrudan döndürülmüyor ama yine de eklenmeli.
- **`Tables` entity adı çoğul** — JPA naming gereksiz. `Table` Java keyword değil ama `jakarta.persistence.Table` ile çakışıyor. `RoomTable`/`PoolTable` daha açık olurdu.

---

## Bireysel Review Dosyaları

| Dosya                                                                        | Kapsam                                                         |
| ---------------------------------------------------------------------------- | -------------------------------------------------------------- |
| [01-auth-review.md](01-auth-review.md)                                       | `/auth/*` endpoint'leri, JWT akışı, invite code                |
| [02-home-review.md](02-home-review.md)                                       | Venue + Table CRUD, manager→owner gap                          |
| [03-session-review.md](03-session-review.md)                                 | Session lifecycle, snapshot, hesaplama, **güvenlik delikleri** |
| [04-reports-review.md](04-reports-review.md)                                 | 8 reports endpoint'i, NPE, bucketing, forecast                 |
| [05-managers-review.md](05-managers-review.md)                               | (Implementasyon yok — gap dökümü + roadmap)                    |
| [06-subscription-review.md](06-subscription-review.md)                       | (Implementasyon yok — gap dökümü + roadmap)                    |
| [07-security-error-handling-review.md](07-security-error-handling-review.md) | JWT detayları, hata zarfı, i18n, EntryPoint, AccessDenied      |
| [08-config-build-review.md](08-config-build-review.md)                       | pom.xml, application.yml, Dockerfile, build/test eksiklikleri  |
