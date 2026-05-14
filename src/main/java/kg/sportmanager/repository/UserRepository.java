package kg.sportmanager.repository;

import kg.sportmanager.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmailOrPhone(String email, String phone);
    Optional<User> findByRefreshToken(String refreshToken);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByHandle(String handle);

    /** Managers API: менеджеры владельца, без soft-deleted, отсортированы по lastSeenAt DESC NULLS LAST, name ASC. */
    @Query("""
            SELECT u FROM User u
            WHERE u.role = :role
              AND u.owner = :owner
              AND u.deletedAt IS NULL
            ORDER BY u.lastSeenAt DESC NULLS LAST, u.name ASC
            """)
    List<User> findManagersByOwner(@Param("role") User.Role role, @Param("owner") User owner);

    /** Reports/Managers: найти менеджера по id с проверкой принадлежности владельцу. */
    Optional<User> findByIdAndOwnerAndDeletedAtIsNull(UUID id, User owner);

    /**
     * Reports detail audit lookup: deleted-status'a bakmadan owner-bound user'ı bulur.
     * Owner manager'ı sildikten sonra geçmiş raporları görüntüleyebilsin diye
     * Reports detay sayfası bunu kullanır; live yönetim akışları
     * {@link #findByIdAndOwnerAndDeletedAtIsNull} kullanmaya devam eder.
     */
    Optional<User> findByIdAndOwner(UUID id, User owner);

    /** Profile: число активных (не soft-deleted) менеджеров у данного владельца. */
    long countByOwnerAndRoleAndDeletedAtIsNull(User owner, User.Role role);

    /** Register/login: ensure uniqueness check ignores soft-deleted (anonymized) rows. */
    boolean existsByEmailAndDeletedAtIsNull(String email);

    boolean existsByPhoneAndDeletedAtIsNull(String phone);

    /** Account-delete cascade: hard-delete every manager (incl. soft-deleted) under owner. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("DELETE FROM User u WHERE u.owner = :owner")
    int deleteAllByOwner(@org.springframework.data.repository.query.Param("owner") User owner);
}