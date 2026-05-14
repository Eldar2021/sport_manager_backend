# Config, Build, Tests & Operations Review

Kod: [pom.xml](../pom.xml), [application.yml](../src/main/resources/application.yml), [Dockerfile](../Dockerfile), [README.md](../README.md), [CLAUDE.md](../CLAUDE.md)

---

## P0 — Kritik

### 1. Hiç test yok

`pom.xml` test scope dependency'leri ekli:
```xml
<dependency>spring-boot-starter-data-jpa-test</dependency>
<dependency>spring-boot-starter-security-test</dependency>
<dependency>spring-boot-starter-webmvc-test</dependency>
```

Ama `src/test/` dizini boş (yalnızca skeleton). Sıfır unit, sıfır integration test. Bu kadar critical bug'a (cross-tenant manager, NPE, race condition) test coverage olmadan ulaşıldı.

**Minimum kapsam önerisi:**

- `AuthServiceImpl`: login (geçerli/geçersiz creds), register (OWNER/MANAGER + invite validation), refresh, invite-code generation.
- `HomeServiceImpl`: venue CRUD, select switch, cascade soft-delete.
- `SessionServiceImpl`: full lifecycle (start → pause → resume → finish), discount math, cancel window, **race condition concurrent start**.
- `ReportsServiceImpl`: overview math, clipped previous, forecast threshold, **CANCELLED session log entry (NPE regression test)**.
- Repository: custom queries with `@DataJpaTest`.

Spring Boot Test + Testcontainers (PostgreSQL) idealdir — Hibernate JPQL postgres-specific davranışlar olabilir.

### 2. Schema versioning yok (Flyway/Liquibase)

`spring.jpa.hibernate.ddl-auto: update` — production'da risk:

- Field rename → eski column kalır, yeni column eklenir (data loss riski yok ama schema garbage).
- Field type değişimi → Hibernate bazen migration yapamaz, runtime hatası.
- Null constraint ekleme → mevcut null veri varsa app start fail.
- Index'leri Hibernate üretmez, manuel eklenmeli.

[CLAUDE.md](../CLAUDE.md) bu riski açıkça yazmış. **Flyway** entegrasyonu önerilir:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate    # ← Flyway schema'yı yönetiyor, Hibernate yalnız doğruluyor
  flyway:
    enabled: true
    locations: classpath:db/migration
```

İlk migration `V1__init.sql` — mevcut schema'yı dökümante eder. Sonrasında `V2__add_user_owner_username.sql`, `V3__subscriptions_table.sql`, vs.

`User.owner`, `User.username`, `Subscription`, `Payment` gibi gelmesi gereken değişiklikler bu mekanizma olmadan production rollout'unda risk yaratır.

### 3. JWT secret commit'lenmiş

[application.yml:23](../src/main/resources/application.yml#L23) — bkz. [07-security-error-handling-review.md](07-security-error-handling-review.md) #6. **`git filter-branch` veya `bfg` ile geçmişten temizlenmesi gerek**, sonra rotate.

### 4. PostgreSQL `sslmode=require` localhost'a karşı

```yaml
url: "jdbc:postgresql://localhost:5432/sport_manager?sslmode=require"
```

Local Postgres genelde SSL configured değil. `./mvnw spring-boot:run` çalıştırması başarısız olabilir. Düzeltme:

```yaml
url: "${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/sport_manager}"
```

Production override ile `?sslmode=require` ekler.

---

## P1 — Yüksek

### 5. Environment-aware config yok

Tek `application.yml`, profile yok. Doc önerisi:

- `application.yml` — minimal default
- `application-dev.yml` — local dev (sslmode disable, hot reload)
- `application-prod.yml` — production (env var'lardan secret'lar)

`.gitignore`'a `application-local.yml` ekle, dev'in kişisel ayarları için.

### 6. Hardcoded DB credentials

```yaml
username: postgres
password: postgres
```

Default dev creds OK ama production override için `${SPRING_DATASOURCE_USERNAME}` syntax'ı kullanılmalı.

### 7. Hikari pool 5 max — çok düşük

```yaml
hikari:
  maximum-pool-size: 5
  minimum-idle: 1
```

5 connection MVP için tutarlı (Heroku hobby tier'ı da 20). Reports endpoint'leri N+1 düzeltilmediği sürece concurrent kullanıcıda pool exhaustion riski. 10-15 daha güvenli.

### 8. Server port hardcoded

```yaml
server:
  port: 8080
```

`${PORT:8080}` ile env override edilebilir hale getir (Heroku, Cloud Run gibi managed platform için gerekli).

### 9. Actuator yok

Health endpoint yok. Cloud platform'lar liveness/readiness probe'u için `/actuator/health` bekler. Docker container restart loop'una girebilir.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 10. Spring Boot 4.0.6 bleeding edge

`pom.xml` `<version>4.0.6</version>` parent. Spring Boot 4.0 GA 2025 Kasım. Stabilizasyon henüz devam ediyor; library uyumluluğu (özellikle Hibernate 7, jjwt) garanti edilmemiş. Production için **3.4.x LTS** veya **3.5.x** daha güvenli.

Geçiş zor değil (deprecation çok az), ama "junior dev'in en yeni'yi seçmesi" tipik bir tuzak.

### 11. `jjwt 0.11.5` outdated

Mevcut sürüm: 0.12.x. API biraz değişti (`parserBuilder()` → `parser()`, `setSigningKey` → `verifyWith`). Upgrade gerek değil ama güvenlik patch'ler için update planla.

### 12. Logger ayarları yok

`logback-spring.xml` veya `application.yml`'da log level config yok. Default DEBUG/INFO çok detaylı. Production için:

```yaml
logging:
  level:
    root: INFO
    org.springframework: WARN
    org.hibernate.SQL: WARN
    kg.sportmanager: INFO
```

`show-sql: false` zaten doğru — production'da SQL'i log'a basmıyor. ✓

### 13. Logging structured (JSON) değil

Cloud logging için JSON output yararlı. `logstash-logback-encoder` veya Spring Boot 3.4+ built-in `STRUCTURED_LOGGING_FORMAT_CONSOLE=ecs` flag'i. MVP scope dışı.

### 14. Dockerfile multi-stage iyi ama optimize edilebilir

[Dockerfile:9-14](../Dockerfile#L9-L14):

```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B
```

OK. `-DskipTests` Docker build'inde test atlatıyor — CI tarafında test çalıştırmak şart, image'a güvenilmemeli.

İyileştirme: Buildah veya jib ile layered jar (Spring Boot 2.4+ destekliyor). Spring Boot 4 `--layers` flag'i ile:

```dockerfile
RUN java -Djarmode=layertools -jar app.jar extract
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
```

Dependency changeset ile rebuild yalnız son layer'ı etkiler → CI hızlanır.

### 15. Dockerfile HEALTHCHECK yok

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
```

(Actuator eklendikten sonra.)

### 16. README outdated

[README.md:69-100](../README.md#L69-L100) eski klasör yapısını gösteriyor:

```
kg.sportmanager/
├── config/
│   ├── SecurityConfiguration.java
│   ├── JwtAuthFilter.java
│   └── MessageConfig.java
```

Gerçek: `configuration/`, `security/` ayrı paketler. Ayrıca README'de sadece auth endpoint'leri var, home/session/reports yok. Mobil dev README'yi okuyup yanlış prefix kullanabilir (`/auth` vs `/api/v1/...`).

---

## P2 — Orta

### 17. `mvnw` permissions

`./mvnw` Linux/macOS'ta executable olmalı. Commit'lenen permission bit'i `git ls-files --stage mvnw` ile kontrol edilmeli.

### 18. `.gitignore` yetersiz olabilir

Repo'da `target/`, `.idea/`, `*.iml` ignore edilmiş mi? Hızlı kontrol:

```bash
cat .gitignore
```

(Bu review içinde okumadım — eksiklik varsa eklenmeli.)

### 19. Docker compose yok

Local dev için Postgres setup'ı manuel. `docker-compose.yml`:

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: sport_manager
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]
    volumes: ["pg_data:/var/lib/postgresql/data"]
volumes:
  pg_data:
```

Yeni dev onboard'unu basitleştirir.

### 20. CI/CD pipeline yok (anlaşılan)

GitHub Actions / GitLab CI dosyası yok (root'ta `.github/`, `.gitlab-ci.yml` görünmüyor). Manuel deploy = "deploy'da bozulur" riski.

Minimum CI:
- Test
- Maven build
- Docker build + tag
- Container registry push

### 21. `pom.xml`'de gereksiz `<scm>`, `<licenses>`, `<developers>` boş tag'ler

```xml
<licenses><license/></licenses>
<developers><developer/></developers>
<scm><connection/>...</scm>
```

`mvn` warning üretebilir. Sil veya doldur.

### 22. `springdoc 2.8.8` Spring Boot 4 uyumluluğu

Springdoc 2.7+ Spring Boot 3.4 destekler. 2.8.8 Spring Boot 4 ile resmi destek listede mi? Build çalışıyor olabilir ama upgrade sırasında dikkat.

### 23. Lombok versiyon explicit yok

`<dependency lombok>` versiyon belirtmemiş — parent BOM'dan geliyor. Spring Boot 4 parent Lombok 1.18.36+ getirir. Java 21 + Lombok 1.18.30+ gerek → OK.

---

## P3 — Düşük

- `SportManagerApplication.java` sadece `@SpringBootApplication` — minimal, OK.
- `messages*.properties` boş — bkz. [07-security-error-handling-review.md](07-security-error-handling-review.md) #4.
- `swagger-ui.html` ve `/v3/api-docs` public ✓.
- `SwaggerConfig` HTTP Bearer scheme ✓.
- Maven wrapper var ✓.
- Multi-stage Dockerfile + non-root user ✓.
- Java 21 ✓ (LTS).

---

## Operations Eksiklikleri

| Konu | Durum | Etki |
|------|-------|------|
| Health endpoint | Yok | Cloud platform restart loop |
| Structured logging | Yok | Cloud log query'leri zor |
| Metrics (Prometheus) | Yok | Performans gözlemi yok |
| Rate limiting | Yok | Brute force / DoS riski |
| Request tracing (W3C Trace Context) | Yok | Distributed debugging zor |
| Backup / DR | Bilinmiyor | Veri kaybı riski |
| Secrets management | env var (önerilen) | KV store (Vault, SecretsManager) ideal |
| Cron job (subscription transition) | Yok | Bkz. [06](06-subscription-review.md) |
| Email (forgot-password) | Yok | Auth akışı eksik |

---

## Eylem Önerileri

### Hemen (P0)

1. **En az 1 smoke test yaz** — login → start session → finish flow. Bu bir gece coverage yaratır.
2. **Flyway entegre et + `V1__init.sql` üret** — `mvn flyway:migrate` ile schema'yı versiyonla. Sonra `ddl-auto: validate`.
3. **JWT secret + DB creds env var'a taşı** — `.gitignore`'a `application-local.yml`.
4. **`sslmode=require` env override** — local'de fail etmesin.

### Bu sprint (P1)

5. **Actuator ekle + `/actuator/health`**.
6. **`application-{dev,prod}.yml` profilleri**.
7. **Hikari pool 15'e çıkar** veya N+1 düzeltildikten sonra ölç.
8. **Spring Boot 3.4.x LTS'e indir** (opsiyonel, 4.0 bug'lar çıkarsa).
9. **README'yi güncelle** — gerçek paket yapısı + tüm endpoint domain'leri.
10. **CI pipeline** — minimum: test + build + image push.

### Sonraki sprint (P2)

11. **Docker compose** — local dev onboard hızlandır.
12. **Logging config** — structured + level'lar.
13. **Rate limiter** — login + register + invite-code başına.
14. **Pom.xml boş tag temizliği**.

---

## Pozitif Noktalar (junior dev için iyi olanlar)

Adil olmak gerekirse, kod tabanı şu açılardan iyi:

- **Layered mimari net** — controller/service/impl/repository/entity ayrımı disiplinli.
- **DTO'lar entity'lerden ayrı** — mass assignment riski yok.
- **Lombok kullanımı tutarlı** — boilerplate yok.
- **AppException + GlobalExceptionHandler pattern'i** — kısmen bozuk olsa da doğru yön.
- **Soft delete uygulanmış** — venue ve table için.
- **Snapshot field'lar session'da** — tarif değişiminden bağımsızlık doğru implement edilmiş.
- **Swagger annotation'ları endpoint'lerde** — API doc otomasyonu kurulmuş.
- **Dockerfile multi-stage + non-root user** — production-ready Docker hygiene.
- **Pessimistic security defaults** — `anyRequest().authenticated()`, stateless session.
- **Reports'taki forecast algoritması** — Linear regression implement edilmiş, MVP scope ile uyumlu.

Bu noktalar projenin "junior tarafından yapılmış ama düşünülmüş" karakterini gösteriyor. Asıl boşluklar **iş mantığı + güvenlik + production hazırlığı** üzerinde.
