# Sport Manager Backend

REST API backend для системы управления спортивными объектами. Реализует аутентификацию, авторизацию через JWT токены и управление пользователями с ролями `OWNER` и `MANAGER`.

---

## Технологии

- **Java 21+**
- **Spring Boot 3**
- **Spring Security**
- **PostgreSQL**
- **JWT (jjwt 0.11.5)**
- **Hibernate / JPA**
- **Lombok**

---

## Требования

- Java 21+
- PostgreSQL 14+
- Maven 3.8+

---

## Установка и запуск

### 1. Клонировать репозиторий

```bash
git clone https://github.com/Eldar2021/sport_manager_backend.git
cd sport_manager_backend
```

### 2. Создать базу данных

```sql
CREATE DATABASE sport_manager;
```

### 3. Настроить `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/sport_manager
    username: postgres
    password: ВАШ_ПАРОЛЬ

jwt:
  secret: ВАШ_СЕКРЕТНЫЙ_КЛЮЧ_МИНИМУМ_256_БИТ
  access-expiration: 900000       # 15 минут
  refresh-expiration: 2592000000  # 30 дней
```

### 4. Запустить проект

```bash
mvn spring-boot:run
```

Сервер запустится на `http://localhost:8080`

---

## Структура проекта

```
kg.sportmanager/
├── config/
│   ├── SecurityConfiguration.java
│   ├── JwtAuthFilter.java
│   └── MessageConfig.java
├── controller/
│   └── AuthController.java
├── service/
│   ├── AuthService.java
│   └── impl/
│       └── AuthServiceImpl.java
├── repository/
│   ├── UserRepository.java
│   └── InviteCodeRepository.java
├── entity/
│   ├── User.java
│   └── InviteCode.java
├── dto/
│   ├── request/
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── RefreshTokenRequest.java
│   │   └── ForgotPasswordRequest.java
│   └── response/
│       ├── AuthResponse.java
│       ├── UserResponse.java
│       └── InviteCodeResponse.java
└── util/
    ├── JwtUtil.java
    └── ErrorResponse.java
```

---

## API Документация

**Base URL:** `http://localhost:8080`  
**Content-Type:** `application/json`

### Глобальные заголовки

| Header            | Описание                          | Пример            |
|-------------------|-----------------------------------|-------------------|
| `Accept-Language` | Язык ответа (`en` / `ru` / `ky`) | `ru`              |
| `Authorization`   | Bearer токен (для protected)      | `Bearer eyJ...`   |
| `os`              | Платформа клиента                 | `ios` / `android` |
| `versionBuild`    | Номер сборки приложения           | `42`              |

---

### Auth Endpoints

#### `POST /auth/login`
Вход в систему.

**Request:**
```json
{
  "username": "test@example.com",
  "password": "Test1234"
}
```

**Response 200:**
```json
{
  "user": {
    "id": "user-001",
    "name": "Test Owner",
    "role": "OWNER",
    "email": "test@example.com",
    "phone": "+996 700 000 001"
  },
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

| HTTP | Код ошибки            | Причина                  |
|------|-----------------------|--------------------------|
| 401  | `INVALID_CREDENTIALS` | Неверный логин или пароль |
| 423  | `ACCOUNT_LOCKED`      | Аккаунт заблокирован     |

---

#### `POST /auth/register`
Регистрация нового пользователя.

**Request (Owner):**
```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "+996 700 000 003",
  "password": "Test1234",
  "role": "OWNER"
}
```

**Request (Manager):**
```json
{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "phone": "+996 700 000 004",
  "password": "Test1234",
  "role": "MANAGER",
  "inviteCode": "INVITE-001"
}
```

| HTTP | Код ошибки            | Причина                        |
|------|-----------------------|--------------------------------|
| 400  | `INVALID_INVITE_CODE` | Неверный или истёкший инвайт   |
| 409  | `EMAIL_ALREADY_USED`  | Email уже зарегистрирован      |
| 409  | `PHONE_ALREADY_USED`  | Телефон уже зарегистрирован    |

---

#### `POST /auth/refresh`
Обновление токенов.

**Request:**
```json
{
  "refreshToken": "eyJhbGc..."
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGc...",
  "refreshToken": "eyJhbGc..."
}
```

| HTTP | Причина                          |
|------|----------------------------------|
| 401  | Refresh token истёк или неверен  |

---

#### `POST /auth/logout`
Выход из системы. Требует `Authorization` заголовок.

**Response:** `200 OK` (пустое тело)

---

#### `POST /auth/forgot-password`
Сброс пароля. Новый пароль отправляется на email.

**Request:**
```json
{
  "email": "user@example.com"
}
```

**Response:** `200 OK`

---

#### `POST /auth/invite-code`
Генерация инвайт-кода для регистрации Manager. Только для роли `OWNER`.

**Response 200:**
```json
{
  "code": "INVITE-A1B2C3D4",
  "expiresAt": "2026-05-04T12:34:56"
}
```

| HTTP | Причина               |
|------|-----------------------|
| 401  | Токен отсутствует     |
| 403  | Нет роли OWNER        |

---

## Формат ошибок

Все ошибки возвращаются в едином формате:

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": {
    "en": "Invalid username or password",
    "ru": "Неверный логин или пароль",
    "ky": "Логин же сырсөз туура эмес"
  }
}
```

---

## Роли пользователей

| Роль      | Описание                                        |
|-----------|-------------------------------------------------|
| `OWNER`   | Владелец. Может генерировать инвайт-коды        |
| `MANAGER` | Менеджер. Регистрируется только по инвайт-коду  |

---

## Поток обновления токенов

```
Клиент → запрос → 401
         ↓
POST /auth/refresh { refreshToken }
   ├─ 200 → сохранить новые токены → повторить запрос
   └─ 401 → очистить данные → экран входа
```

---

## Разработка

```bash
# Сборка
mvn clean install

# Запуск тестов
mvn test

# Запуск с профилем dev
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

---
