package kg.sportmanager.service.impl;

import kg.sportmanager.dto.request.CancelSessionRequest;
import kg.sportmanager.dto.request.FinishSessionRequest;
import kg.sportmanager.dto.request.StartSessionRequest;
import kg.sportmanager.dto.response.SessionLiteResponse;
import kg.sportmanager.dto.response.SessionResultResponse;
import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.security.RequiresActiveSubscription;
import kg.sportmanager.service.SessionService;
import kg.sportmanager.util.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@RequiresActiveSubscription
public class SessionServiceImpl implements SessionService {

    /** Окно отмены для менеджера — 60 секунд. */
    private static final long CANCEL_WINDOW_SECONDS = 60L;

    private final SessionRepository sessionRepository;
    private final TableRepository tableRepository;
    private final SessionMapper mapper;

    // ─────────────────────────────────────────────────────────────────────────
    // START
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SessionLiteResponse start(User user, StartSessionRequest request) {
        UUID tableId = parseUuid(request.getTableId());

        Tables table = tableRepository.findByIdAndDeletedAtIsNull(tableId)
                .orElseThrow(() -> new AppException("TABLE_NOT_FOUND", HttpStatus.NOT_FOUND));

        // Проверяем права доступа к столу (стол должен принадлежать owner'у пользователя)
        validateTableAccess(user, table);

        // Нельзя стартовать, если на столе уже есть активная/paused сессия
        if (sessionRepository.existsByTableAndIsActiveTrue(table)) {
            throw new AppException("TABLE_HAS_ACTIVE_SESSION", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now(); // Время пишет backend — критическое правило

        Session session = Session.builder()
                .table(table)
                .isActive(true)
                .isPaused(false)
                .startedAt(now)
                .totalPausedSeconds(0)
                .tarifAmountSnapshot(table.getTarifAmount())
                .tarifTypeSnapshot(table.getTarifType())
                .manager(user)
                .status(Session.SessionStatus.ACTIVE)
                .build();

        sessionRepository.save(session);
        return mapper.toLite(session);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAUSE
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SessionLiteResponse pause(User user, String sessionId) {
        Session session = findActiveSession(sessionId);
        validateTableAccess(user, session.getTable());

        // Сессия должна быть в статусе ACTIVE (не PAUSED)
        if (session.getStatus() != Session.SessionStatus.ACTIVE) {
            throw new AppException("SESSION_NOT_ACTIVE", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now(); // Время пишет backend

        session.setPaused(true);
        session.setPausedAt(now);
        session.setStatus(Session.SessionStatus.ACTIVE); // статус остаётся ACTIVE, isPaused=true

        // Нет отдельного статуса PAUSED в enum — используем isPaused флаг.
        // Но по API-спецификации status в ответе должен быть "PAUSED",
        // поэтому заводим вспомогательный маппинг через флаг isPaused.

        sessionRepository.save(session);
        return mapper.toLite(session);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESUME
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SessionLiteResponse resume(User user, String sessionId) {
        Session session = findActiveSession(sessionId);
        validateTableAccess(user, session.getTable());

        // Сессия должна быть в паузе
        if (!session.isPaused()) {
            throw new AppException("SESSION_NOT_PAUSED", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now(); // Время пишет backend

        // Накопительно суммируем паузу
        long pausedDuration = now.getEpochSecond() - session.getPausedAt().getEpochSecond();
        session.setTotalPausedSeconds(session.getTotalPausedSeconds() + (int) pausedDuration);

        session.setPaused(false);
        session.setResumedAt(now);
        session.setPausedAt(null);

        sessionRepository.save(session);
        return mapper.toLite(session);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FINISH
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SessionResultResponse finish(User user, String sessionId, FinishSessionRequest request) {
        Session session = findActiveSession(sessionId);
        validateTableAccess(user, session.getTable());

        int discountPercent = request.getDiscountPercent() == null ? 0 : request.getDiscountPercent();
        if (discountPercent < 0 || discountPercent > 100) {
            throw new AppException("INVALID_DISCOUNT", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Instant now = Instant.now(); // Время пишет backend

        // Если сессия была на паузе — автоматически resume перед finish
        int totalPausedSeconds = session.getTotalPausedSeconds();
        if (session.isPaused() && session.getPausedAt() != null) {
            long pausedDuration = now.getEpochSecond() - session.getPausedAt().getEpochSecond();
            totalPausedSeconds += (int) pausedDuration;
        }

        // Расчёт billableSeconds
        long totalSeconds = now.getEpochSecond() - session.getStartedAt().getEpochSecond();
        int billableSeconds = (int) Math.max(0, totalSeconds - totalPausedSeconds);

        // Расчёт subtotal в зависимости от тарифа
        int subtotal = calculateSubtotal(billableSeconds, session.getTarifAmountSnapshot(), session.getTarifTypeSnapshot());

        // Финализируем сессию
        session.setActive(false);
        session.setPaused(false);
        session.setPausedAt(null);
        session.setEndedAt(now);
        session.setTotalPausedSeconds(totalPausedSeconds);
        session.setDurationSeconds(billableSeconds);
        session.setStatus(Session.SessionStatus.COMPLETED);

        // totalAmount сохраняем в entity
        int discountAmount = Math.round(subtotal * discountPercent / 100f);
        session.setTotalAmount((long) (subtotal - discountAmount));

        sessionRepository.save(session);
        return mapper.toResult(session, discountPercent, subtotal);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANCEL
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SessionResultResponse cancel(User user, String sessionId, CancelSessionRequest request) {
        Session session = findActiveSession(sessionId);
        validateTableAccess(user, session.getTable());

        // Валидация reason
        if (request.getReason() == null || request.getReason().isBlank()
                || request.getReason().length() > 200) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Проверка окна отмены для менеджера
        if (user.getRole() == User.Role.MANAGER) {
            long secondsSinceStart = Instant.now().getEpochSecond() - session.getStartedAt().getEpochSecond();
            if (secondsSinceStart > CANCEL_WINDOW_SECONDS) {
                throw new AppException("CANCEL_WINDOW_EXPIRED", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        Instant now = Instant.now(); // Время пишет backend

        session.setActive(false);
        session.setPaused(false);
        session.setEndedAt(now);
        session.setStatus(Session.SessionStatus.CANCELLED);
        session.setCancelReason(request.getReason());
        // durationSeconds, totalAmount остаются null для CANCELLED

        sessionRepository.save(session);
        return mapper.toResult(session, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ищем активную (isActive=true) сессию по ID.
     * Для COMPLETED/CANCELLED сессий isActive=false → SESSION_ALREADY_COMPLETED.
     */
    private Session findActiveSession(String sessionId) {
        UUID id = parseUuid(sessionId);
        Session session = sessionRepository.findById(id)
                .orElseThrow(() -> new AppException("SESSION_NOT_FOUND", HttpStatus.NOT_FOUND));

        if (!session.isActive()) {
            throw new AppException("SESSION_ALREADY_COMPLETED", HttpStatus.CONFLICT);
        }
        return session;
    }

    /**
     * Проверяет, что пользователь имеет доступ к столу.
     * Owner: стол должен принадлежать его заведению.
     * Manager: стол должен принадлежать его owner-у (см. User.owner — Faz 1).
     *
     * ВНИМАНИЕ: пока User.owner не заполнен (Faz 1), Manager-ы блокируются полностью,
     * чтобы закрыть cross-tenant утечку. После Faz 1 проверка будет: tableOwner == user.owner.
     */
    private void validateTableAccess(User user, Tables table) {
        User tableOwner = table.getVenue().getOwner();
        User userOwner = user.getRole() == User.Role.OWNER ? user : user.getOwner();
        if (userOwner == null || !tableOwner.getId().equals(userOwner.getId())) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Рассчитывает subtotal согласно тарифу.
     * Стандартное математическое округление (0.5 → вверх).
     */
    private int calculateSubtotal(int billableSeconds, int tarifAmount, Tables.TarifType tarifType) {
        double units = switch (tarifType) {
            case MINUTE -> billableSeconds / 60.0;
            case HOUR   -> billableSeconds / 3600.0;
            case DAY    -> billableSeconds / 86400.0;
        };
        return (int) Math.round(units * tarifAmount);
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception e) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}