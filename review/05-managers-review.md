# Managers Review — IMPLEMENTASYON YOK

Doc kaynağı: [docs/managers-api.md](../docs/managers-api.md)  
Kod: **yok**

---

## Durum

Doc tanımlı endpoint'ler:

| Method   | Path                    | Doc                         | Implementasyon |
| -------- | ----------------------- | --------------------------- | -------------- |
| `GET`    | `/api/v1/managers`      | OWNER bağlı manager listesi | ❌ Hiç yok     |
| `DELETE` | `/api/v1/managers/{id}` | Manager'ı takımdan çıkar    | ❌ Hiç yok     |

Bağımlı doc bileşenleri:

- `ManagerModel` (`id`, `name`, `username`, `lastSeenAt`) — DTO yok
- `MANAGER_NOT_FOUND`, `HAS_ACTIVE_SESSION`, `FORBIDDEN` hata kodları — `MANAGER_NOT_FOUND` Reports'ta kullanılıyor ama MESSAGES'a register edilmemiş

Etkilenen mobil ekranları:

- "Managers" ekranı — boş veya 404
- Invite Code generate ekranı çalışıyor ama oluşan manager'ı listelemek için endpoint yok
- Manager silme akışı tamamen yok

---

## Gap Analizi

### Schema eksikleri

Manager listesi/silme için bağımlı entity değişiklikleri:

1. **`User.owner`** — Manager hangi owner'a bağlı? Schema'da yok (bkz. [01-auth-review.md](01-auth-review.md) #8).
2. **`User.username`** — Doc `username: string ('@' olmadan)`. User entity'de yok (bkz. [04-reports-review.md](04-reports-review.md) #7).
3. **`User.lastSeenAt`** — Login/refresh zamanı veya en son authenticated istek. Schema'da yok. Implementasyon önerisi: `JwtAuthFilter` başarılı doğrulamadan sonra `user.lastSeenAt = now` set etsin (her isteği yazmamak için 5 dk throttling).

### Endpoint mantığı (önerilen)

**`GET /api/v1/managers`** — OWNER-only.

```java
@GetMapping("/api/v1/managers")
public ResponseEntity<List<ManagerResponse>> list(@AuthenticationPrincipal User user) {
    if (user.getRole() != User.Role.OWNER) throw new AppException("FORBIDDEN", FORBIDDEN);
    return ResponseEntity.ok(managerService.listByOwner(user));
}
```

Sıralama: doc belirtmemiş, öneri `lastSeenAt DESC NULLS LAST, name ASC`.

**`DELETE /api/v1/managers/{id}`** — OWNER-only.

Doc'a göre aktif session'ı olan manager silinmek istenirse `409 HAS_ACTIVE_SESSION` (opsiyonel). Implementasyon:

```java
@Transactional
public void delete(User owner, UUID managerId) {
    User manager = userRepository.findById(managerId)
        .filter(m -> m.getRole() == Role.MANAGER)
        .filter(m -> m.getOwner() != null && m.getOwner().getId().equals(owner.getId()))
        .orElseThrow(() -> new AppException("MANAGER_NOT_FOUND", NOT_FOUND));

    if (sessionRepository.existsByManagerAndIsActiveTrue(manager)) {
        throw new AppException("HAS_ACTIVE_SESSION", CONFLICT);  // opsiyonel
    }
    // Soft delete önerisi (geçmiş reports için)
    manager.setDeletedAt(Instant.now());
    userRepository.save(manager);
}
```

> Not: Şu anki `User` entity'sinde `deletedAt` yok — eklenmeli. Soft delete'in Reports tarafında `findSessionLog` gibi sorguları etkilememesi gerekir; session.manager FK silinen user'a referans tutmaya devam etmeli.

---

## Implementation Roadmap

### Adım 1 — Schema migration

`User` entity'sine eklenmeli:

```java
@ManyToOne(fetch = LAZY) @JoinColumn(name = "owner_id")
private User owner;

@Column(unique = true)
private String username;

private Instant lastSeenAt;

private Instant deletedAt;
```

`AuthServiceImpl.register`'da username üret (örn. email local part + suffix), MANAGER için `owner` set et (InviteCode'tan).

`ddl-auto: update` ile bu alanlar otomatik ADD COLUMN olur ama mevcut MANAGER kullanıcılar için `owner_id` ve `username` NULL kalır → backfill migration gerekir. Flyway/Liquibase eksikliği burada acı veriyor (bkz. [08-config-build-review.md](08-config-build-review.md)).

### Adım 2 — DTO

```java
@Data @Builder
public class ManagerResponse {
    private String id;
    private String name;
    private String username;
    private Instant lastSeenAt;
}
```

### Adım 3 — Controller + Service + Repository

`ManagersController` `/api/v1/managers`, `ManagerService` interface + impl, `UserRepository`'ye:

```java
List<User> findByRoleAndOwnerAndDeletedAtIsNullOrderByLastSeenAtDesc(Role role, User owner);
```

### Adım 4 — Hata kodlarını MESSAGES'a ekle

`MANAGER_NOT_FOUND`, `HAS_ACTIVE_SESSION` (Reports ve Session henüz `HAS_ACTIVE_SESSION` kullanmıyor ama Managers için gerekecek — Session'ın `TABLE_HAS_ACTIVE_SESSION` ile karıştırılmamalı; ayrı kod).

### Adım 5 — Last-seen update mekanizması

`JwtAuthFilter`'a:

```java
Instant last = user.getLastSeenAt();
if (last == null || ChronoUnit.MINUTES.between(last, Instant.now()) > 5) {
    user.setLastSeenAt(Instant.now());
    userRepository.save(user);
}
```

Her istek için bir UPDATE pahalı. 5dk throttle uygula. Veya async (event publisher) ile yaz.

### Adım 6 — Subscription gate ile hookla

Managers silme: read-only mu kabul edilir? Doc tablosu (subscription-api.md):

> | Manager invite | 403 SUBSCRIPTION_REQUIRED |

Silme tablosu yok ama "yazma" kategorisinde sayılırsa gate uygulanır. Karar konuşulmalı.

---

## Mobil İmpact

Mobil paket `packages/managers` doc'a göre yazılmış varsayalım. Backend implementasyonu olmadan:

- Manager listesi ekranı boş array veya 404 görür (404 = uygun).
- Manager detail (Reports `/managers/{id}` ile farklı) gerek yok — `managers-api.md` detail endpoint'i tanımlamamış.
- Silme akışı çalışmaz.

Geçici workaround: Backend `/api/v1/managers` → 501 Not Implemented dönsün, mobil "Coming soon" empty state göstersin. Veya tüm endpoint'i implement edip MVP'ye kat.

---

## Effort Tahmini

Schema değişiklikleri + 2 endpoint + DTO + last-seen mekanizması:

- Backend yarı gün (senior dev)
- Schema migration scripti yarım gün (Flyway eklenmesi dahil)
- Test yazımı 2 saat
- Toplam: ~1 gün

Bu task **04-reports-review.md** #7 (`username` field) ve **01-auth-review.md** #8 (`User.owner` field) ile aynı schema migration'da yapılabilir — toplam ~1.5 gün.
