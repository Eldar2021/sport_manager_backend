# Ревью Config, Build, Tests & Operations

Код: [pom.xml](../../pom.xml), [application.yml](../../src/main/resources/application.yml), [Dockerfile](../../Dockerfile), [README.md](../../README.md), [CLAUDE.md](../../CLAUDE.md)

---

## P0 — Критично

### 1. Нет тестов

В `pom.xml` подключены тестовые зависимости:

```xml
<dependency>spring-boot-starter-data-jpa-test</dependency>
<dependency>spring-boot-starter-security-test</dependency>
<dependency>spring-boot-starter-webmvc-test</dependency>
```

Но каталог `src/test/` пуст (только скелет). Ноль unit-тестов, ноль integration. С таким количеством критичных багов (cross-tenant manager, NPE, race condition) совершенно неудивительно, что они дошли до прода.

**Минимально необходимое покрытие:**

- `AuthServiceImpl`: login (валид/невалид), register (OWNER/MANAGER + проверка invite), refresh, генерация invite-кода.
- `HomeServiceImpl`: CRUD venue, переключение selected, каскадный soft-delete.
- `SessionServiceImpl`: полный lifecycle (start → pause → resume → finish), арифметика discount, окно cancel, **race condition в параллельном start**.
- `ReportsServiceImpl`: математика overview, clipped previous, порог forecast, **regression-тест на NPE для CANCELLED-сессии в лог менеджера**.
- Repositories: кастомные запросы через `@DataJpaTest`.

Идеально — Spring Boot Test + Testcontainers (PostgreSQL): часть JPQL зависит от Postgres-специфики.

### 2. Нет версионирования схемы (Flyway/Liquibase)

`spring.jpa.hibernate.ddl-auto: update` — в проде риск:

- Переименование поля → старая колонка остаётся, новая добавляется (потери данных нет, но мусор в схеме).
- Смена типа поля → Hibernate иногда не справится, runtime-ошибка.
- Добавление NOT NULL → если есть null-данные — приложение не стартует.
- Индексы Hibernate сам не создаёт.

[CLAUDE.md](../../CLAUDE.md) этот риск прямо подсвечивает. Рекомендую **Flyway**:

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
      ddl-auto: validate # ← Flyway управляет схемой, Hibernate только валидирует
  flyway:
    enabled: true
    locations: classpath:db/migration
```

Первая миграция `V1__init.sql` — снимок текущей схемы. Дальше — `V2__add_user_owner_username.sql`, `V3__subscriptions_table.sql` и т.д.

Все будущие изменения (`User.owner`, `User.username`, `Subscription`, `Payment`) без этого механизма создают риск при выкате в прод.

### 3. JWT secret в репозитории

[application.yml:23](../../src/main/resources/application.yml#L23) — см. [07-security-error-handling-review.md](07-security-error-handling-review.md) #6. **Нужно вычистить из истории `git filter-branch` или `bfg`** и ротировать секрет.

### 4. `sslmode=require` для localhost

```yaml
url: "jdbc:postgresql://localhost:5432/sport_manager?sslmode=require"
```

Локальный Postgres обычно без SSL. `./mvnw spring-boot:run` может не запуститься. Исправление:

```yaml
url: "${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/sport_manager}"
```

В проде override-ом добавляется `?sslmode=require`.

---

## P1 — Высокий

### 5. Нет environment-aware конфигов

Единственный `application.yml`, профилей нет. Предложение:

- `application.yml` — минимальный default
- `application-dev.yml` — локалка (без sslmode, hot reload)
- `application-prod.yml` — прод (секреты из env)

В `.gitignore` — `application-local.yml` для личных настроек разработчика.

### 6. Hardcoded DB credentials

```yaml
username: postgres
password: postgres
```

Для дев-окружения OK, но для override в проде нужен `${SPRING_DATASOURCE_USERNAME}`.

### 7. Hikari pool 5 — мало

```yaml
hikari:
  maximum-pool-size: 5
  minimum-idle: 1
```

5 connections нормально для MVP (даже Heroku hobby — 20). Но пока N+1 в Reports не починен — при конкурентных запросах риск исчерпать пул. Безопаснее 10-15.

### 8. Server port захардкожен

```yaml
server:
  port: 8080
```

Сделать `${PORT:8080}` (для Heroku, Cloud Run и других managed-платформ).

### 9. Нет Actuator

Нет health-эндпоинта. Cloud-платформы ждут `/actuator/health` для liveness/readiness. Контейнер может зациклиться в restart loop.

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

### 10. Spring Boot 4.0.6 — bleeding edge

В `pom.xml` parent `<version>4.0.6</version>`. GA Spring Boot 4.0 — ноябрь 2025. Стабилизация ещё идёт; совместимость библиотек (Hibernate 7, jjwt) не гарантирована. Для прода безопаснее **3.4.x LTS** или **3.5.x**.

Откат несложный (мало deprecation), но «junior выбрал самое свежее» — типовая ловушка.

### 11. `jjwt 0.11.5` устарел

Актуальная версия — 0.12.x. API изменился (`parserBuilder()` → `parser()`, `setSigningKey` → `verifyWith`). Обновляться не обязательно, но запланировать апдейт для security-patch'ей.

### 12. Нет настройки логгера

`logback-spring.xml` или `application.yml` без log-level. По умолчанию DEBUG/INFO — слишком подробно. Для прода:

```yaml
logging:
  level:
    root: INFO
    org.springframework: WARN
    org.hibernate.SQL: WARN
    kg.sportmanager: INFO
```

`show-sql: false` уже корректен — SQL в логи в проде не уходит. ✓

### 13. Логи не структурированные (JSON)

Для cloud-логирования JSON полезен. `logstash-logback-encoder` или встроенный в Spring Boot 3.4+ флаг `STRUCTURED_LOGGING_FORMAT_CONSOLE=ecs`. Вне MVP.

### 14. Multi-stage Dockerfile хорош, но можно оптимизировать

[Dockerfile:9-14](../../Dockerfile#L9-L14):

```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B
```

OK. `-DskipTests` в Docker build пропускает тесты — поэтому **обязательно** запускать тесты в CI и не доверять образу как «уже протестированному».

Улучшение: Buildah / jib для layered jar (Spring Boot 2.4+). С `--layers`:

```dockerfile
RUN java -Djarmode=layertools -jar app.jar extract
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
```

Тогда при изменении только кода rebuild затрагивает только последний слой → CI ускоряется.

### 15. В Dockerfile нет HEALTHCHECK

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1
```

(После подключения Actuator.)

### 16. README устарел

[README.md:69-100](../../README.md#L69-L100) описывает старую структуру:

```
kg.sportmanager/
├── config/
│   ├── SecurityConfiguration.java
│   ├── JwtAuthFilter.java
│   └── MessageConfig.java
```

На самом деле: `configuration/`, `security/` — отдельные пакеты. Кроме того, в README только auth-эндпоинты, нет home/session/reports. Мобильный dev может прочитать README и зашить неправильный префикс (`/auth` вместо `/api/v1/...`).

---

## P2 — Средний

### 17. Права на `mvnw`

`./mvnw` должен быть executable на Linux/macOS. Проверка commit-битов — `git ls-files --stage mvnw`.

### 18. `.gitignore` может быть неполным

В репо `target/`, `.idea/`, `*.iml` в ignore? Быстрая проверка:

```bash
cat .gitignore
```

(В этом ревью не открывал — если пробелы есть, дополнить.)

### 19. Нет docker-compose

Локальный dev делает Postgres-setup руками. `docker-compose.yml`:

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

Ускоряет onboarding новых разработчиков.

### 20. Нет CI/CD pipeline (предположительно)

В корне не видно `.github/`, `.gitlab-ci.yml`. Деплой вручную → классический риск «сломается на деплое».

Минимум CI:

- Тесты
- Maven build
- Docker build + tag
- Push в container registry

### 21. В `pom.xml` лишние пустые `<scm>`, `<licenses>`, `<developers>`

```xml
<licenses><license/></licenses>
<developers><developer/></developers>
<scm><connection/>...</scm>
```

`mvn` может ругаться warning'ами. Удалить или заполнить.

### 22. Совместимость `springdoc 2.8.8` со Spring Boot 4

Springdoc 2.7+ официально поддерживает Spring Boot 3.4. 2.8.8 для Spring Boot 4 — в матрице совместимости? Build идёт, но при апгрейдах быть осторожными.

### 23. Версия Lombok не указана явно

У зависимости `lombok` версия не задана — берётся из parent BOM. Spring Boot 4 parent даёт Lombok 1.18.36+. Для Java 21 нужно 1.18.30+ → OK.

---

## P3 — Низкий

- `SportManagerApplication.java` минимальный с `@SpringBootApplication` — OK.
- `messages*.properties` пустые — см. [07-security-error-handling-review.md](07-security-error-handling-review.md) #4.
- `swagger-ui.html` и `/v3/api-docs` публичные ✓.
- `SwaggerConfig` с HTTP Bearer scheme ✓.
- Maven wrapper есть ✓.
- Multi-stage Dockerfile + non-root ✓.
- Java 21 ✓ (LTS).

---

## Пробелы в Operations

| Тема                                  | Состояние             | Эффект                                    |
| ------------------------------------- | --------------------- | ----------------------------------------- |
| Health endpoint                       | Нет                   | Restart loop в cloud                      |
| Структурированные логи                | Нет                   | Сложные запросы в cloud-логах             |
| Метрики (Prometheus)                  | Нет                   | Нет наблюдаемости                         |
| Rate limiting                         | Нет                   | Brute force / DoS                         |
| Request tracing (W3C Trace Context)   | Нет                   | Сложный distributed debugging             |
| Backup / DR                           | Неизвестно            | Риск потери данных                        |
| Управление секретами                  | env var (предложение) | Идеально KV-стор (Vault, Secrets Manager) |
| Cron job (переходы статусов подписки) | Нет                   | См. [06](06-subscription-review.md)       |
| Email (forgot-password)               | Нет                   | Auth-флоу неполный                        |

---

## Рекомендации

### Сейчас (P0)

1. **Написать хотя бы 1 smoke-test** — login → start session → finish. Это даёт хоть какой-то coverage с одного захода.
2. **Подключить Flyway + сгенерировать `V1__init.sql`** — версионировать схему через `mvn flyway:migrate`, затем `ddl-auto: validate`.
3. **Перенести JWT secret + DB-креды в env-var** — `.gitignore` для `application-local.yml`.
4. **Сделать `sslmode=require` переопределяемым** — чтобы локально не падало.

### В этом спринте (P1)

5. **Добавить Actuator + `/actuator/health`**.
6. **Профили `application-{dev,prod}.yml`**.
7. **Hikari pool до 15** или измерить после починки N+1.
8. **Откатиться на Spring Boot 3.4.x LTS** (опционально, если 4.0 даст багов).
9. **Обновить README** — реальная структура + все домены эндпоинтов.
10. **CI pipeline** — минимум: тесты + build + push image.

### В следующем спринте (P2)

11. **docker-compose** — ускорить onboard разработчиков.
12. **Логи** — structured + уровни.
13. **Rate limiter** — login + register + invite-code.
14. **Подчистить пустые теги в pom.xml**.

---

## Положительные стороны (то, что junior сделал хорошо)

Справедливости ради, код имеет несколько сильных мест:

- **Чёткая многослойная архитектура** — controller/service/impl/repository/entity дисциплинированно разнесены.
- **DTO отдельно от сущностей** — нет mass assignment.
- **Lombok используется последовательно** — нет boilerplate.
- **Паттерн AppException + GlobalExceptionHandler** — хоть и сломан местами, направление верное.
- **Soft-delete** для venue и table.
- **Snapshot-поля в Session** — корректно реализован принцип «изменение тарифа не задевает текущие сессии».
- **Swagger-аннотации на эндпоинтах** — автогенерация API doc настроена.
- **Multi-stage Dockerfile + non-root** — хорошая прод-гигиена.
- **Безопасные дефолты** — `anyRequest().authenticated()`, stateless session.
- **Forecast в Reports с линейной регрессией** — реализован в рамках MVP.

Эти моменты говорят о том, что проект «сделан джуном, но с головой». Главные пробелы — в **бизнес-логике, безопасности и production-готовности**.
