# План реализации (темп Claude)

Дата: 2026-05-14  
Источник: [review/00-summary.md](00-summary.md) и доменные файлы ревью  
Цель: довести бэкенд до production-ready — дорожная карта в реальном AI-темпе

> **Философия оценки:** эта версия меряет время **сессиями Claude**, а не «человеко-днями». Одна сессия = 1.5-3 часа активной работы + твоё ревью/решения между ними. Оценки junior/senior приведены в скобках для сравнения.

---

## Резюме

В бэкенде **5 критических пробелов**, **2 недостающих API**, **~40 P0/P1 багов**. План — 5 фаз:

| Фаза       | Время Claude                                  | Сравнение (senior) | Содержание                                                | Результат                                           |
| ---------- | --------------------------------------------- | ------------------ | --------------------------------------------------------- | --------------------------------------------------- |
| **Фаза 0** | 30-45 мин (1 сессия)                          | 3-4 часа           | Срочные security/data fixes                               | NPE, пароль в логах, secret, race condition закрыты |
| **Фаза 1** | 1.5-2 часа (1 сессия)                         | 1 день             | Фундамент схемы — Flyway + User.owner/username/lastSeenAt | База для всего последующего                         |
| **Фаза 2** | 1.5 часа (1 сессия)                           | 1 день             | Формат ошибок + security                                  | Единый ErrorResponse, MESSAGES заполнен             |
| **Фаза 3** | 2-3 часа (1-2 сессии)                         | 2 дня              | Недостающие API (Managers + Subscription)                 | SaaS revenue работает                               |
| **Фаза 4** | 3-4 часа (2 сессии)                           | 3 дня              | Production-ready (тесты, CI, perf, ops)                   | Деплоится, наблюдается                              |
| **Итого**  | **~10-12 часов активной работы** (4-5 сессий) | **~7 дней**        |                                                           |                                                     |

**Реальный wall-clock:** марафон за один день возможен, но не рекомендуется. **3-5 календарных дней** с PR-циклами реалистично (между сессиями — твоё ревью + мобильный QA).

---

## Что определяет время

Меня тормозят и **требуют человеческого участия** следующие вещи:

| Тема                               | Влияние                             | Решение                                             |
| ---------------------------------- | ----------------------------------- | --------------------------------------------------- |
| **7 решений** (ниже)               | 5-15 мин ожидания каждое            | Ответь заранее → одна сессия = одна фаза            |
| **Ротация JWT secret**             | Destructive, нужны твои руки в prod | Делается у тебя на глазах                           |
| **Чистка git history (bfg)**       | Force-push, откатить нельзя         | Подтверди → я даю команды                           |
| **Backfill SQL (manager → owner)** | Без audit log — вручную             | Дай список / правило «новые менеджеры с такой даты» |
| **Maven build циклы**              | Каждый `./mvnw verify` — 1-2 минуты | Запускать пачкой, разбирать результаты вместе       |
| **Mobile integration test**        | Мобильная команда не в моих руках   | На стадии PR — уведомить мобильных                  |
| **Production deploy**              | Твоя цепочка апрувов                | Сначала staging, потом prod (rollback готов)        |

Без этих блокеров: писал бы код одной непрерывной сессией. С ними: **1-2 фазы на сессию**.

---

## Решения, которые нужны до старта

Прими их заранее — не хочешь же, чтобы я в каждой сессии ждал:

1. **Префикс пути** — `/auth/**` → `/api/v1/auth/**`? (См. [01](01-auth-review.md) #3)
   - Моя рекомендация ✅: **A — перенести код.** Мобила пишет по docs.

2. **Поведение `VENUE_HAS_TABLES`** — cascade soft-delete или «сначала удалите столы»? (См. [02](02-home-review.md) #7)
   - Моя рекомендация ✅: **Cascade** (так уже делает код). Удалить `VENUE_HAS_TABLES` из docs.

3. **Forgot-password** — реальный Spring Mail или `SERVICE_UNAVAILABLE`? (См. [01](01-auth-review.md) #1)
   - Моя рекомендация ✅: **`SERVICE_UNAVAILABLE` для MVP**, email-интеграция в Фазу 5.

4. **Rollout подписки** — mock-режим + Finik позже? (См. [06](06-subscription-review.md))
   - Моя рекомендация ✅: **Mock + gate в launch.** Finik integration — Фаза 5+.

5. **Версия Spring Boot** — 4.0.6 или 3.4 LTS? (См. [08](08-config-build-review.md) #10)
   - Моя рекомендация ✅: **Откат на 3.4 LTS** (стабильность > новые фичи).

6. **Переименование `Tables` → `RoomTable`**? (косметика)
   - Моя рекомендация ✅: **Не делать** — стоимость рефакторинга высокая, в MVP мало пользы.

7. **i18n стратегия** — заполнить properties? (См. [07](07-security-error-handling-review.md) #4)
   - Моя рекомендация ✅: **Заполнить properties** + читать через `MessageSource`.

**Если скажешь «принимаю все рекомендации» — план идёт без ожидания решений.**

---

## Фаза 0 — Срочные правки (Claude: 30-45 мин)

**Цель:** Production-blocker баги. **Закрывается за одну сессию.**

### Задача 0.1 — Reports NPE (Claude: 2 мин)

Файл: [ReportsServiceImpl.java:583](../src/main/java/kg/sportmanager/service/impl/ReportsServiceImpl.java#L583)

```java
.durationSeconds(s.getDurationSeconds() == null ? null : s.getDurationSeconds().longValue())
```

Regression-тест: для менеджера с CANCELLED-сессией endpoint отвечает 200.

### Задача 0.2 — Убрать пароли из логов (Claude: 3 мин)

В `LoginRequest`, `RegisterRequest`, `RefreshTokenRequest`, `ForgotPasswordRequest` — `@ToString.Exclude` на чувствительные поля. Из контроллеров убрать логирование payload'ов целиком.

### Задача 0.3 — Ротация JWT secret (Claude: 5 мин кода + твои руки)

1. `application.yml` → `secret: "${JWT_SECRET}"` (без default)
2. Сгенерировать новый: `openssl rand -base64 48`
3. **Твои руки:** очистка git history (`bfg --replace-text passwords.txt`), force-push
4. **Твои руки:** в prod env установить `JWT_SECRET`

**Без твоего одобрения шаги 3-4 не делаются.**

### Задача 0.4 — Изоляция cross-tenant менеджеров (Claude: 5 мин)

**Временный** фикс [SessionServiceImpl.validateTableAccess](../src/main/java/kg/sportmanager/service/impl/SessionServiceImpl.java#L240-L252):

```java
case MANAGER -> false;  // В Фазе 1 нормально через User.owner
```

Менеджер вообще не сможет трогать сессии. Уведомить мобильную команду: «через 24 часа Фаза 1 всё починит».

### Задача 0.5 — Unique constraint для активной сессии (Claude: 3 мин + твой доступ к БД)

```sql
CREATE UNIQUE INDEX one_active_session_per_table
  ON sessions (table_id) WHERE is_active = true;
```

Пока нет Flyway → либо вручную, либо как миграция после задачи 1.1.

### Задача 0.6 — `details: "null"` литерально (Claude: 2 мин)

В [JwtAuthEntryPoint](../src/main/java/kg/sportmanager/security/JwtAuthEntryPoint.java) и [JwtAccessDeniedHandler](../src/main/java/kg/sportmanager/security/JwtAccessDeniedHandler.java) — `HashMap`, `put("details", null)`.

### Фаза 0 — Чистое время

- Код: ~15 мин
- Build/test: ~10 мин
- Твои действия (secret, БД): ~15 мин
- **Итого: 30-45 мин** одной сессией

---

## Фаза 1 — Фундамент схемы (Claude: 1.5-2 часа)

**Цель:** Версионирование схемы + связь User.owner. **Закрывается одной сессией, но для backfill нужна твоя помощь.**

### Задача 1.1 — Подключение Flyway (Claude: 20 мин)

- В `pom.xml` — Flyway dependency
- В `application.yml` — `ddl-auto: validate`, Flyway enable
- **Твои руки:** `pg_dump --schema-only` — отдай мне, я напишу `V1__initial.sql`
- Выполнить `flyway:baseline`

### Задача 1.2 — Расширить сущность `User` (Claude: 15 мин)

В `User` добавить `owner`, `username`, `lastSeenAt`, `deletedAt` + миграция `V2__user_owner_username.sql`.

### Задача 1.3 — Backfill-скрипт (Claude: 5 мин на скрипт + твоё одобрение)

- Для существующих OWNER username = local-part email (автоматически)
- Для существующих MANAGER `owner_id`: **дай мне список или возьмём из invite_codes по логам** (если audit нет — придётся вручную)

### Задача 1.4 — Починить `resolveOwner` + `validateTableAccess` (Claude: 10 мин)

Убрать временный фикс из Фазы 0, поставить нормальный. Прогнать smoke-тест.

### Задача 1.5 — `AuthServiceImpl.register` ставит owner для MANAGER (Claude: 5 мин)

```java
if (request.getRole() == User.Role.MANAGER) {
    user.setOwner(invite.getOwner());
    user.setUsername(generateUsername(request.getEmail()));
}
```

### Задача 1.6 — `ReportsRepository.managerStats` использует username (Claude: 2 мин)

В JPQL `s.manager.email` → `s.manager.username`.

### Задача 1.7 — `JwtAuthFilter` обновляет lastSeenAt (Claude: 10 мин)

С троттлингом 5 мин. Unit-тест.

### Задача 1.8 — Smoke-тесты (Claude: 30 мин)

Manager: login → venue list → session start → в своём venue 200, в чужом 403. `@SpringBootTest` интеграция.

### Фаза 1 — Чистое время

- Код + тесты: ~75 мин
- Build/test циклы: ~20 мин
- Сбор данных для backfill (твоя сторона): неизвестно
- **Итого: 1.5-2 часа** + возможный доп-проход на backfill

---

## Фаза 2 — Формат ошибок + Security (Claude: 1.5 часа)

**Одна сессия, внешних блокеров нет.**

### Задача 2.1 — Добавить `ErrorResponse.details` (Claude: 3 мин)

### Задача 2.2 — `AuthServiceImpl` → `AppException` (Claude: 10 мин)

### Задача 2.3 — Заполнить `messages*.properties` (Claude: 25 мин — en/ru/ky × ~30 кодов)

### Задача 2.4 — `GlobalExceptionHandler` через MessageSource (Claude: 10 мин)

### Задача 2.5 — Jakarta `@Valid` + аннотации DTO (Claude: 20 мин)

### Задача 2.6 — Улучшения JWT (UTF-8, expired vs invalid, type claim, CORS) (Claude: 20 мин)

### Задача 2.7 — `TokenPairResponse` (убрать user из refresh) (Claude: 5 мин)

### Фаза 2 — Чистое время

- Код + тесты: ~95 мин
- Build циклы: ~15 мин
- **Итого: ~1.5-2 часа** одной сессией

---

## Фаза 3 — Недостающие API (Claude: 2-3 часа / 1-2 сессии)

### Задача 3.1 — Managers API (Claude: 30 мин)

`ManagerService` + `ManagerController` + DTO + repository + 2 эндпоинта + тест. В одной сессии.

### Задача 3.2 — Subscription Entities + Service (Claude: 40 мин)

`Subscription`, `Payment` + `V3__subscriptions_payments.sql` + repository + каркас сервиса + status recompute + ConfigurationProperties.

### Задача 3.3 — Subscription Endpoints (Claude: 40 мин)

5 эндпоинтов + DTO + validation + тесты.

### Задача 3.4 — Subscription Gate (AOP) (Claude: 25 мин)

Аннотация `@RequiresActiveSubscription` + аспект + применить ко всем write-эндпоинтам.

### Задача 3.5 — Хук TRIAL в register + Daily cron (Claude: 15 мин)

`@EnableScheduling` + cron + создание TRIAL при регистрации OWNER.

### Задача 3.6 — Backfill подписок (Claude: 5 мин скрипт + твой доступ к БД)

Миграция `V4__backfill_trial.sql`.

### Фаза 3 — Чистое время

- Код + тесты: ~155 мин
- Build циклы: ~25 мин
- **Итого: ~3 часа** — в одну сессию сложно, **эффективнее в 2 сессии** (3.1+3.2 первая, 3.3+3.4+3.5+3.6 вторая).

---

## Фаза 4 — Production-Ready (Claude: 3-4 часа / 2 сессии)

### Задача 4.1 — Test coverage 60%+ (Claude: 90 мин — самая длинная часть)

Testcontainers + unit/integration для 5 сервисов. **Build-циклы тестов удлиняют фазу.**

### Задача 4.2 — Починка N+1 (Claude: 30 мин)

`countByVenue`, `findActiveByTables`, `aggregateByTable`, GROUP BY revenue series.

### Задача 4.3 — Actuator + Health (Claude: 10 мин)

### Задача 4.4 — Профили конфига (Claude: 15 мин)

`application-{dev,prod}.yml` + `.gitignore`.

### Задача 4.5 — CI/CD pipeline (Claude: 20 мин)

`.github/workflows/ci.yml` — тесты + build + Docker push. **GitHub-доступ — у тебя.**

### Задача 4.6 — Rate limiting (Claude: 25 мин)

Bucket4j + применить к login/register/invite-code.

### Задача 4.7 — README + Swagger (Claude: 20 мин)

### Задача 4.8 — Structured logging + MDC (Claude: 15 мин)

### Задача 4.9 — docker-compose для dev (Claude: 5 мин)

### Фаза 4 — Чистое время

- Код + тесты: ~230 мин (~4 часа)
- Build циклы: ~30 мин (из-за тестов)
- **Итого: ~4 часа** — **разбить на 2 сессии**: первая (4.1 тесты), вторая (всё остальное).

---

## Предлагаемое расписание сессий

| Сессия | Время | Содержание                                              | Что делаешь между                         |
| ------ | ----- | ------------------------------------------------------- | ----------------------------------------- |
| 1      | 1 ч   | Фаза 0 (срочные)                                        | Подтвердить ротацию secret, запустить SQL |
| 2      | 2 ч   | Фаза 1 (схема)                                          | Данные для backfill, уведомить мобильных  |
| 3      | 1.5 ч | Фаза 2 (ошибки + security)                              | PR review                                 |
| 4      | 1.5 ч | Фаза 3 первая половина (Managers + Subscription entity) | Подтвердить стратегию подписок            |
| 5      | 1.5 ч | Фаза 3 вторая половина (endpoints + gate + cron)        | PR review                                 |
| 6      | 2 ч   | Фаза 4 тесты                                            | Разобрать результаты тестов вместе        |
| 7      | 1.5 ч | Фаза 4 остаток (perf + CI + ops)                        | Настроить GitHub Actions, деплой staging  |

**Итого:** 7 сессий × в среднем 1.5 ч = **~11 часов активной работы Claude**, по календарю — **3-5 дней** распределённо или **2 марафонных дня**.

---

## Марафон-режим (за один день?)

**Технически:** Да, если:

1. Все 7 решений заранее «принимаю рекомендации» (~0 мин ожидания)
2. Для ротации JWT secret у тебя под рукой prod env-доступ
3. Для backfill согласишься на «новые менеджеры автоматически, старых разберём потом»
4. Во время build'ов терпеливо ждёшь, не отвлекаешься
5. Mobile QA откладываешь на пост-PR

**Реальный wall-clock:** 8-10 часов. Старт 09:00 — к 19:00 PR готов.

**На практике:** ты не выдержишь 10 часов рядом со мной (созвоны, другая работа). Поэтому **3-5 дней распределённо** — эффективнее.

---

## Риски (причины срыва сроков)

| Риск                          | Вероятность | Эффект                   | Митигация                                               |
| ----------------------------- | ----------- | ------------------------ | ------------------------------------------------------- |
| Баг в Spring Boot 4.0         | Средняя     | +2 ч (откат версии)      | Smoke-тест в начале Фазы 0                              |
| Нет audit log для backfill    | Высокая     | +1 день (ручные данные)  | Правило «новые менеджеры» + поговорить со старыми OWNER |
| Ошибки мобильной интеграции   | Средняя     | +2-4 ч на фазу           | Contract-тесты перед PR                                 |
| Production deploy упал        | Низкая      | +1 день (rollback + fix) | Сначала staging                                         |
| Циклы тестов (Testcontainers) | Высокая     | +1-2 ч (Фаза 4)          | Параллельный запуск в CI                                |
| Задержки твоих апрувов        | Высокая     | +N часов                 | Все решения заранее                                     |

---

## Чек-листы по фазам (кратко)

**Фаза 0 ✅** = NPE, пароль в логах, secret, race condition, details-null — всё закрыто.  
**Фаза 1 ✅** = Flyway работает, User.owner — на месте, менеджер корректно изолирован.  
**Фаза 2 ✅** = Единый формат ошибок, validation-framework, MESSAGES заполнен.  
**Фаза 3 ✅** = Managers + Subscription работают, gate активен.  
**Фаза 4 ✅** = Тесты 60%, CI зелёный, Actuator health, structured log, нет N+1.

---

## Момент решения

Скажи одно из:

- **A) «Марафон — начни сегодня, закрой за день.»** → Прими все 7 решений сейчас, я погнал.
- **B) «По одной фазе за сессию — распределённо.»** → Стартуем с Фазы 0, между сессиями твоё ревью.
- **C) «Закрой только P0, остальное потом.»** → Сделаю Фазу 0, потом пауза.
- **D) «План готов, пока не стартуй.»** → Документы на руках, по сигналу начну.

Какой вариант?
