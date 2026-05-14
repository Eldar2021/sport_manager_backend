package kg.sportmanager.service.impl;

import kg.sportmanager.dto.response.ManagerResponse;
import kg.sportmanager.entity.User;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.UserRepository;
import kg.sportmanager.service.ManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ManagerServiceImpl implements ManagerService {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ManagerResponse> listByOwner(User owner) {
        requireOwner(owner);
        return userRepository
                .findManagersByOwner(User.Role.MANAGER, owner)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    @kg.sportmanager.security.RequiresActiveSubscription
    public void delete(User owner, String managerId) {
        requireOwner(owner);
        UUID id = parseUuid(managerId);

        User manager = userRepository.findByIdAndOwnerAndDeletedAtIsNull(id, owner)
                .filter(u -> u.getRole() == User.Role.MANAGER)
                .orElseThrow(() -> new AppException("MANAGER_NOT_FOUND", HttpStatus.NOT_FOUND));

        if (sessionRepository.existsByManagerAndIsActiveTrue(manager)) {
            throw new AppException("HAS_ACTIVE_SESSION", HttpStatus.CONFLICT);
        }

        // Soft delete — preserves historical reports referencing this manager
        manager.setDeletedAt(Instant.now());
        manager.setRefreshToken(null); // logout-on-delete
        userRepository.save(manager);
    }

    private void requireOwner(User user) {
        if (user.getRole() != User.Role.OWNER) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception e) {
            throw new AppException("MANAGER_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
    }

    private ManagerResponse toResponse(User user) {
        return ManagerResponse.builder()
                .id(user.getId().toString())
                .name(user.getName())
                .username(user.getHandle())
                .lastSeenAt(user.getLastSeenAt())
                .build();
    }
}
