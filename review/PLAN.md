# Uygulama Planı (Claude tempo)

Tarih: 2026-05-14  
Kaynak: [review/00-summary.md](00-summary.md) ve domain bazlı review dosyaları  
Hedef: Backend'i production-ready hale getirmek — gerçek AI tempolu yol haritası

> **Süre felsefesi:** Bu sürüm, klasik "iş günü" yerine **Claude oturumları** ile ölçüyor. Bir oturum = 1.5-3 saat aktif çalışma + senin review/karar arası. Junior/senior tahminleri karşılaştırma amaçlı parantezde.

---

## Yönetici Özeti

Backend'de **5 kritik gap**, **2 ana eksik API**, **~40 P0/P1 bug** var. Plan 5 faz halinde:

| Faz        | Claude süresi                      | Karşılaştırma (senior) | Kapsam                                                  | Çıktı                                            |
| ---------- | ---------------------------------- | ---------------------- | ------------------------------------------------------- | ------------------------------------------------ |
| **Faz 0**  | 30-45 dk (1 oturum)                | 3-4 saat               | Acil security/data fix                                  | NPE, log şifre, secret, race condition kapatılır |
| **Faz 1**  | 1.5-2 saat (1 oturum)              | 1 gün                  | Schema temeli — Flyway + User.owner/username/lastSeenAt | Tüm sonraki işlerin altyapısı                    |
| **Faz 2**  | 1.5 saat (1 oturum)                | 1 gün                  | Hata zarfı + güvenlik standardı                         | Tek format ErrorResponse, MESSAGES dolu          |
| **Faz 3**  | 2-3 saat (1-2 oturum)              | 2 gün                  | Eksik API'lar (Managers + Subscription)                 | SaaS revenue modeli çalışır                      |
| **Faz 4**  | 3-4 saat (2 oturum)                | 3 gün                  | Production-ready (test, CI, perf, ops)                  | Deploy-edilebilir, izlenebilir                   |
| **Toplam** | **~10-12 saat aktif** (4-5 oturum) | **~7 gün**             |                                                         |                                                  |

**Gerçek wall-clock:** 1 maraton günü mümkün ama önerilmez. **3-5 takvim günü** PR cycle'ları ile gerçekçi (her oturum arası senin review + mobile QA).

---

## Süreyi Belirleyen Faktörler

Beni yavaşlatan ve **insan girdisine bağlı olan** şeyler:

| Konu                               | Etki                                  | Çözüm                                                         |
| ---------------------------------- | ------------------------------------- | ------------------------------------------------------------- |
| **7 karar** (aşağıda)              | Her birinde 5-15 dk bekleme           | Önceden cevapla → bir oturumda tüm Faz çıkar                  |
| **JWT secret rotate**              | Destructive, prod env'de senin elinle | Senin önünde yapılır                                          |
| **Git history temizleme (bfg)**    | Force-push, geri dönüşü zor           | Onayla → ben komutları veririm                                |
| **Backfill SQL (manager → owner)** | Audit log yoksa manuel                | Liste sağla / "yeni manager'lar bu tarihten sonra" kuralı koy |
| **Maven build cycle'ları**         | Her `./mvnw verify` 1-2 dakika        | Toplu çalıştır, sonuçları beraber yorumla                     |
| **Mobile integration test**        | Mobil ekip elimde değil               | PR aşamasında mobil ekibe haber                               |
| **Production deploy**              | Senin onay zinciri                    | Staging önce, sonra prod (rollback hazır)                     |

Bu blocker'lar olmasaydı: kod yazımı tek nefeste biterdi. Mevcut sınırlamalar göz önüne alındığında: **oturum başına 1-2 Faz**.

---

## Karar Gereken Konular (planı başlatmadan önce)

Bu kararları önceden ver — her oturum başında bekleyeyim istemezsen:

1. **Path prefix** — `/auth/**` → `/api/v1/auth/**`'a taşınacak mı? (Bkz. [01](01-auth-review.md) #3)
   - Önerim ✅: **A — kodu taşı.** Mobil docs'tan implement ediyor.

2. **`VENUE_HAS_TABLES` davranışı** — cascade soft-delete mi yoksa "önce sil" mi? (Bkz. [02](02-home-review.md) #7)
   - Önerim ✅: **Cascade** (kod zaten öyle yapıyor). Doc'tan `VENUE_HAS_TABLES` kodu silinir.

3. **Forgot-password** — Spring Mail ile gerçek mi, `SERVICE_UNAVAILABLE` mı? (Bkz. [01](01-auth-review.md) #1)
   - Önerim ✅: **MVP için `SERVICE_UNAVAILABLE` dön, mail entegrasyonu Faz 5'e.**

4. **Subscription rollout** — mock-mode launch + Finik sonra mı? (Bkz. [06](06-subscription-review.md))
   - Önerim ✅: **Mock + gate ile launch.** Finik integration Faz 5+.

5. **Spring Boot sürümü** — 4.0.6 mı, 3.4 LTS mi? (Bkz. [08](08-config-build-review.md) #10)
   - Önerim ✅: **3.4 LTS'e in** (production stability > yeni feature).

6. **`Tables` → `RoomTable`** rename? (kozmetik)
   - Önerim ✅: **Yapma** — refactor maliyeti yüksek, MVP'de değer düşük.

7. **i18n stratejisi** — properties dolduralım mı? (Bkz. [07](07-security-error-handling-review.md) #4)
   - Önerim ✅: **Properties doldur** + `MessageSource` ile oku.

**Hepsini "önerim kabul" dersen, plan ekstra 0 karar bekleme süresi ile çalışır.**

---

## Faz 0 — Acil Fix (Claude: 30-45 dk)

**Hedef:** Production-blocker bug'lar. **Tek oturumda biter.**

### Görev 0.1 — Reports NPE (Claude: 2 dk)

Dosya: [ReportsServiceImpl.java:583](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L583)

```java
.durationSeconds(s.getDurationSeconds() == null ? null : s.getDurationSeconds().longValue())
```

Regression test ekle: CANCELLED session içeren manager için 200 dönmeli.

### Görev 0.2 — Şifre log silme (Claude: 3 dk)

`LoginRequest`, `RegisterRequest`, `RefreshTokenRequest`, `ForgotPasswordRequest` DTO'larında hassas alanlara `@ToString.Exclude`. Controller'lardan payload log'larını kaldır.

### Görev 0.3 — JWT secret rotate (Claude: 5 dk kod + senin elin)

1. `application.yml` → `secret: "${JWT_SECRET}"` (default'suz)
2. Yeni secret üret: `openssl rand -base64 48`
3. **Senin elin:** Git history temizle (`bfg --replace-text passwords.txt`), force-push
4. **Senin elin:** Production env'de `JWT_SECRET` ayarla

**Senin onayın olmadan adım 3-4'e geçmem.**

### Görev 0.4 — Cross-tenant manager izolasyonu (Claude: 5 dk)

[SessionServiceImpl.validateTableAccess](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252) için **geçici** fix:

```java
case MANAGER -> false;  // Faz 1'de User.owner ile düzgün yapılacak
```

Manager session'a hiç dokunamaz hale gelir. Mobile ekibe haber: "24 saat içinde Faz 1 ile düzelir."

### Görev 0.5 — Session start unique constraint (Claude: 3 dk + senin DB access)

```sql
CREATE UNIQUE INDEX one_active_session_per_table
  ON sessions (table_id) WHERE is_active = true;
```

Şu an Flyway yok → ya manuel SQL ya da Faz 1.1 sonrası migration olarak.

### Görev 0.6 — `details: "null"` literal (Claude: 2 dk)

[JwtAuthEntryPoint](../src/main/java/kg/sportmanager/security/JwtAuthEntryPoint.java) ve [JwtAccessDeniedHandler](../src/main/java/kg/sportmanager/security/JwtAccessDeniedHandler.java)'da `HashMap` kullan, `put("details", null)`.

### Faz 0 — Net Süre

- Kod yazımı: ~15 dk
- Build/test: ~10 dk
- Senin müdahaleler (secret, DB): ~15 dk
- **Toplam: 30-45 dk** tek oturum

---

## Faz 1 — Schema Temeli (Claude: 1.5-2 saat)

**Hedef:** Schema versioning + User.owner ilişkisi. **Tek oturumda biter ama backfill için senin yardımına ihtiyacım var.**

### Görev 1.1 — Flyway entegrasyonu (Claude: 20 dk)

- `pom.xml`'e Flyway dependency
- `application.yml`'da `ddl-auto: validate`, Flyway enable
- **Senin elin:** `pg_dump --schema-only` veya bana ver, ben `V1__initial.sql` yazayım
- `flyway:baseline` çalıştır

### Görev 1.2 — `User` entity'sini genişlet (Claude: 15 dk)

`User`'a `owner`, `username`, `lastSeenAt`, `deletedAt` alanları + `V2__user_owner_username.sql` migration.

### Görev 1.3 — Backfill scripti (Claude: 5 dk script + senin onayın)

- Mevcut OWNER için username = email local-part (otomatik)
- Mevcut MANAGER için `owner_id`: **bana bir liste ver veya invite_codes tablosundan log varsa onu kullanayım** (audit log yoksa manuel atama gerek)

### Görev 1.4 — `resolveOwner` + `validateTableAccess` düzelt (Claude: 10 dk)

Faz 0'daki geçici fix'i kaldır, doğru implementasyonu koy. Smoke test çalıştır.

### Görev 1.5 — `AuthServiceImpl.register` MANAGER için owner set et (Claude: 5 dk)

```java
if (request.getRole() == User.Role.MANAGER) {
    user.setOwner(invite.getOwner());
    user.setUsername(generateUsername(request.getEmail()));
}
```

### Görev 1.6 — `ReportsRepository.managerStats` username kullan (Claude: 2 dk)

JPQL'da `s.manager.email` → `s.manager.username`.

### Görev 1.7 — `JwtAuthFilter` lastSeenAt güncelleme (Claude: 10 dk)

5 dk throttle ile last-seen update. Unit test ekle.

### Görev 1.8 — Smoke test paketi (Claude: 30 dk)

Manager login → venue list → session start → kendi venue'sinde 200, başka owner'da 403. `@SpringBootTest` ile entegrasyon.

### Faz 1 — Net Süre

- Kod + test: ~75 dk
- Build/test cycle'ları: ~20 dk
- Backfill veri toplama (senin tarafın): bilinmiyor
- **Toplam: 1.5-2 saat** + backfill için potansiyel ek tur

---

## Faz 2 — Hata Zarfı + Güvenlik (Claude: 1.5 saat)

**Tek oturumda biter, dış bağımlılık yok.**

### Görev 2.1 — `ErrorResponse.details` ekle (Claude: 3 dk)

### Görev 2.2 — `AuthServiceImpl` → `AppException` (Claude: 10 dk)

### Görev 2.3 — `messages*.properties` doldur (Claude: 25 dk — en/ru/ky × ~30 kod)

### Görev 2.4 — `GlobalExceptionHandler` MessageSource (Claude: 10 dk)

### Görev 2.5 — Jakarta `@Valid` + DTO annotations (Claude: 20 dk)

### Görev 2.6 — JWT improvements (UTF-8, expired vs invalid, type claim, CORS) (Claude: 20 dk)

### Görev 2.7 — `TokenPairResponse` (refresh'ten user kaldır) (Claude: 5 dk)

### Faz 2 — Net Süre

- Kod + test: ~95 dk
- Build cycle'ları: ~15 dk
- **Toplam: ~1.5-2 saat** tek oturum

---

## Faz 3 — Eksik API'lar (Claude: 2-3 saat / 1-2 oturum)

### Görev 3.1 — Managers API (Claude: 30 dk)

`ManagerService` + `ManagerController` + DTO + repository + 2 endpoint + test. Tek oturum içinde.

### Görev 3.2 — Subscription Entities + Service (Claude: 40 dk)

`Subscription`, `Payment` entity + `V3__subscriptions_payments.sql` + repository + service iskeleti + status recompute logic + ConfigurationProperties.

### Görev 3.3 — Subscription Endpoints (Claude: 40 dk)

5 endpoint + DTO'lar + validation + test.

### Görev 3.4 — Subscription Gate (AOP) (Claude: 25 dk)

`@RequiresActiveSubscription` annotation + aspect + tüm yazma endpoint'lerine uygula.

### Görev 3.5 — Register TRIAL hook + Daily cron (Claude: 15 dk)

`@EnableScheduling` + cron job + register'da TRIAL üret.

### Görev 3.6 — Subscription backfill (Claude: 5 dk script + senin DB access)

`V4__backfill_trial.sql` migration.

### Faz 3 — Net Süre

- Kod + test: ~155 dk
- Build cycle'ları: ~25 dk
- **Toplam: ~3 saat** — tek oturumda zor, **2 oturuma bölmek daha verimli** (3.1+3.2 birinci, 3.3+3.4+3.5+3.6 ikinci).

---

## Faz 4 — Production-Ready (Claude: 3-4 saat / 2 oturum)

### Görev 4.1 — Test coverage %60+ (Claude: 90 dk — en uzun parça)

Testcontainers + 5 servis için unit/integration test. **Test cycle'ları bu fazı uzatır.**

### Görev 4.2 — N+1 sorgu fix (Claude: 30 dk)

`countByVenue`, `findActiveByTables`, `aggregateByTable`, GROUP BY revenue series.

### Görev 4.3 — Actuator + Health (Claude: 10 dk)

Dependency + config + permitAll.

### Görev 4.4 — Profile bazlı config (Claude: 15 dk)

`application-{dev,prod}.yml` + `.gitignore`.

### Görev 4.5 — CI/CD pipeline (Claude: 20 dk)

`.github/workflows/ci.yml` — test + build + Docker push. **GitHub erişimi senin.**

### Görev 4.6 — Rate limiting (Claude: 25 dk)

Bucket4j + login/register/invite-code'a uygula.

### Görev 4.7 — README + Swagger güncelle (Claude: 20 dk)

### Görev 4.8 — Structured logging + MDC (Claude: 15 dk)

### Görev 4.9 — docker-compose dev (Claude: 5 dk)

### Faz 4 — Net Süre

- Kod + test: ~230 dk (~4 saat)
- Build cycle'ları: ~30 dk (testler yoğun olduğu için)
- **Toplam: ~4 saat** — **2 oturuma böl**: birinci (4.1 testler) + ikinci (kalan hepsi).

---

## Önerilen Oturum Programı

| Oturum | Süre     | Kapsam                                              | Senin işin (oturum arası)                     |
| ------ | -------- | --------------------------------------------------- | --------------------------------------------- |
| 1      | 1 saat   | Faz 0 (acil fix)                                    | Secret rotation onayla, manuel SQL çalıştır   |
| 2      | 2 saat   | Faz 1 (schema)                                      | Backfill verisi ver, mobile ekibi bilgilendir |
| 3      | 1.5 saat | Faz 2 (hata zarfı + security)                       | PR review                                     |
| 4      | 1.5 saat | Faz 3 birinci yarı (Managers + Subscription entity) | Subscription stratejisi onayla                |
| 5      | 1.5 saat | Faz 3 ikinci yarı (endpoints + gate + cron)         | PR review                                     |
| 6      | 2 saat   | Faz 4 testler                                       | Test sonuçlarını birlikte oku                 |
| 7      | 1.5 saat | Faz 4 kalan (perf + CI + ops)                       | GitHub Actions ayarla, staging deploy         |

**Toplam:** 7 oturum × ortalama 1.5 saat = **~11 saat aktif Claude çalışması**, takvim olarak **3-5 gün** dağıtık ya da **2 yoğun gün** maraton.

---

## Maraton Mod (1 günde biter mi?)

**Teknik olarak:** Evet, eğer:

1. Tüm 7 kararı önceden "önerim kabul" dersen (~0 dk bekleme)
2. JWT secret rotation için elin altında prod env access olsun
3. Backfill için "yeni MANAGER'lar bu tarihten sonra otomatik, eskisini sonra hallederiz" der geçersen
4. Build sırasında konuşmadan beklersen
5. Mobile QA'yı PR sonrasına ertelersen

**Gerçek wall-clock:** 8-10 saat. Sabah 09:00 başla, akşam 19:00 PR hazır.

**Pratikte:** Sen 10 saat boyunca yanımda kalamazsın (toplantı, başka iş). O yüzden **3-5 gün dağıtık** daha verimli.

---

## Riskler (zaman aşımı sebepleri)

| Risk                                       | Olasılık | Etki                    | Azaltma                                            |
| ------------------------------------------ | -------- | ----------------------- | -------------------------------------------------- |
| Spring Boot 4.0 bug'ı                      | Orta     | +2 saat (sürüm geri al) | Faz 0 başlangıcında smoke test                     |
| Backfill için audit log yok                | Yüksek   | +1 gün (manuel veri)    | "Yeni manager'lar" kuralı + eski OWNER'larla görüş |
| Mobile entegrasyon hataları                | Orta     | +2-4 saat per faz       | PR'lardan önce contract test                       |
| Production deploy başarısız                | Düşük    | +1 gün (rollback + fix) | Staging önce                                       |
| Test cycle'ları (özellikle Testcontainers) | Yüksek   | +1-2 saat (Faz 4)       | CI'da paralel çalıştır                             |
| Senin onaylar gecikirse                    | Yüksek   | +N saat                 | Önceden tüm kararları al                           |

---

## Faz Sonrası Kontrol Listeleri (özet)

**Faz 0 ✅** = NPE, password log, secret, race condition, details-null hepsi düzelmiş.  
**Faz 1 ✅** = Flyway aktif, User.owner çalışıyor, manager doğru izolasyonda.  
**Faz 2 ✅** = Tek format error, validation framework, MESSAGES dolu.  
**Faz 3 ✅** = Managers + Subscription endpoint'ler çalışıyor, gate aktif.  
**Faz 4 ✅** = Test %60, CI yeşil, Actuator health, structured log, N+1 yok.

---

## Karar Anı

Bana şunlardan birini söyle:

- **A) "Maraton — bugün başla, tek günde bitir."** → 7 kararı şimdi al, ben hızla geçeyim.
- **B) "Dağıtık — bir oturumda bir faz."** → Faz 0 ile başlayalım, her oturum sonu review.
- **C) "Sadece P0'ları çöz, gerisini sonra."** → Faz 0 yap, mola.
- **D) "Plan tamam, henüz başlama."** → Doc'lar elimde, ne zaman istersen.

Hangisi?
