package kg.sportmanager.service.impl;

import kg.sportmanager.dto.request.SelectVenueRequest;
import kg.sportmanager.dto.request.TableRequest;
import kg.sportmanager.dto.request.VenueRequest;
import kg.sportmanager.dto.response.*;
import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.User;
import kg.sportmanager.entity.Venue;
import kg.sportmanager.exception.AppException;
import kg.sportmanager.mapper.HomeMapper;
import kg.sportmanager.repository.SessionRepository;
import kg.sportmanager.repository.TableRepository;
import kg.sportmanager.repository.VenueRepository;
import kg.sportmanager.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HomeServiceImpl implements HomeService {

    private final VenueRepository venueRepository;
    private final TableRepository tableRepository;
    private final SessionRepository sessionRepository;
    private final HomeMapper mapper;


    @Override
    public List<VenueResponse> getVenueList(User user) {
        List<Venue> venues = venueRepository
                .findByOwnerAndDeletedAtIsNullOrderByNumberAscCreatedAtAsc(resolveOwner(user));

        return venues.stream().map(v -> {
            int count = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(v).size();
            return mapper.toVenueResponse(v, count);
        }).toList();
    }


    @Override
    @Transactional
    public SelectedVenueResponse getSelectedVenue(User user) {
        User owner = resolveOwner(user);
        Venue venue = venueRepository.findByOwnerAndSelectedTrueAndDeletedAtIsNull(owner)
                .orElseGet(() -> autoSelectOldest(owner));

        return buildSelectedVenueResponse(venue);
    }


    @Override
    @Transactional
    public SelectedVenueResponse updateSelectedVenue(User user, SelectVenueRequest request) {
        User owner = resolveOwner(user);
        UUID venueId = parseUuid(request.getVenueId());

        Venue newSelected = venueRepository.findByIdAndDeletedAtIsNull(venueId)
                .filter(v -> v.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new AppException("VENUE_NOT_FOUND", HttpStatus.NOT_FOUND));

        venueRepository.findByOwnerAndSelectedTrueAndDeletedAtIsNull(owner).ifPresent(old -> {
            old.setSelected(false);
            venueRepository.save(old);
        });

        newSelected.setSelected(true);
        venueRepository.save(newSelected);

        return buildSelectedVenueResponse(newSelected);
    }


    @Override
    @Transactional
    public VenueResponse createVenue(User user, VenueRequest request) {
        requireOwner(user);
        validateVenueRequest(request);

        if (venueRepository.existsByOwnerAndNumberAndDeletedAtIsNull(user, request.getNumber())) {
            throw new AppException("VENUE_NUMBER_TAKEN", HttpStatus.CONFLICT);
        }

        boolean isFirst = venueRepository
                .findByOwnerAndDeletedAtIsNullOrderByNumberAscCreatedAtAsc(user).isEmpty();

        Venue venue = Venue.builder()
                .owner(user)
                .name(request.getName())
                .number(request.getNumber())
                .address(request.getAddress())
                .selected(isFirst)
                .build();

        venueRepository.saveAndFlush(venue);
        return mapper.toVenueResponse(venue, 0);
    }


    @Override
    @Transactional
    public VenueResponse updateVenue(User user, String venueId, VenueRequest request) {
        requireOwner(user);
        validateVenueRequest(request);

        Venue venue = findVenueOwnedBy(user, parseUuid(venueId));

        if (venueRepository.existsByOwnerAndNumberAndIdNotAndDeletedAtIsNull(
                user, request.getNumber(), venue.getId())) {
            throw new AppException("VENUE_NUMBER_TAKEN", HttpStatus.CONFLICT);
        }

        venue.setName(request.getName());
        venue.setNumber(request.getNumber());
        venue.setAddress(request.getAddress());
        venueRepository.save(venue);

        int count = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue).size();
        return mapper.toVenueResponse(venue, count);
    }

    @Override
    @Transactional
    public DeleteResponse deleteVenue(User user, String venueId) {
        requireOwner(user);
        Venue venue = findVenueOwnedBy(user, parseUuid(venueId));

        if (sessionRepository.existsByTable_VenueAndIsActiveTrue(venue)) {
            throw new AppException("TABLE_HAS_ACTIVE_SESSION", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        List<Tables> tables = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue);
        tables.forEach(t -> t.setDeletedAt(now));
        tableRepository.saveAll(tables);

        venue.setDeletedAt(now);
        if (venue.isSelected()) {
            venueRepository.findFirstByOwnerAndDeletedAtIsNullOrderByCreatedAtAsc(user)
                    .filter(v -> !v.getId().equals(venue.getId()))
                    .ifPresent(v -> {
                        v.setSelected(true);
                        venueRepository.save(v);
                    });
        }
        venueRepository.save(venue);

        return DeleteResponse.builder().id(venueId).deleted(true).build();
    }


    @Override
    @Transactional
    public TableResponse createTable(User user, TableRequest request) {
        requireOwner(user);
        validateTableRequest(request);

        Venue venue = findVenueOwnedBy(user, parseUuid(request.getVenueId()));

        if (tableRepository.existsByVenueAndNumberAndDeletedAtIsNull(venue, request.getNumber())) {
            throw new AppException("TABLE_NUMBER_TAKEN", HttpStatus.CONFLICT);
        }

        Tables table = Tables.builder()
                .venue(venue)
                .name(request.getName())
                .number(request.getNumber())
                .description(request.getDescription())
                .tarifAmount(request.getTarifAmount())
                .currency(request.getCurrency())
                .tarifType(request.getTarifType())
                .build();

        tableRepository.save(table);
        return mapper.toTableResponse(table, null);
    }

    @Override
    @Transactional
    public TableResponse updateTable(User user, String tableId, TableRequest request) {
        requireOwner(user);
        validateTableUpdateRequest(request);

        Tables table = findTableOwnedBy(user, parseUuid(tableId));

        if (tableRepository.existsByVenueAndNumberAndIdNotAndDeletedAtIsNull(
                table.getVenue(), request.getNumber(), table.getId())) {
            throw new AppException("TABLE_NUMBER_TAKEN", HttpStatus.CONFLICT);
        }

        table.setName(request.getName());
        table.setNumber(request.getNumber());
        table.setDescription(request.getDescription());
        table.setTarifAmount(request.getTarifAmount());
        table.setCurrency(request.getCurrency());
        table.setTarifType(request.getTarifType());
        tableRepository.save(table);

        Session session = sessionRepository.findByTableAndIsActiveTrue(table).orElse(null);
        return mapper.toTableResponse(table, session);
    }

    @Override
    @Transactional
    public DeleteResponse deleteTable(User user, String tableId) {
        requireOwner(user);
        Tables table = findTableOwnedBy(user, parseUuid(tableId));

        if (sessionRepository.existsByTableAndIsActiveTrue(table)) {
            throw new AppException("TABLE_HAS_ACTIVE_SESSION", HttpStatus.CONFLICT);
        }

        table.setDeletedAt(Instant.now());
        tableRepository.save(table);

        return DeleteResponse.builder().id(tableId).deleted(true).build();
    }


    private SelectedVenueResponse buildSelectedVenueResponse(Venue venue) {
        List<Tables> tables = tableRepository.findByVenueAndDeletedAtIsNullOrderByNumberAsc(venue);
        int count = tables.size();

        List<TableResponse> tableResponses = mapper.toTableResponseList(tables,
                t -> sessionRepository.findByTableAndIsActiveTrue(t).orElse(null));

        return SelectedVenueResponse.builder()
                .venue(mapper.toVenueResponse(venue, count))
                .tables(tableResponses)
                .build();
    }

    private Venue autoSelectOldest(User owner) {
        Venue oldest = venueRepository.findFirstByOwnerAndDeletedAtIsNullOrderByCreatedAtAsc(owner)
                .orElseThrow(() -> new AppException("VENUE_NOT_FOUND", HttpStatus.NOT_FOUND));
        oldest.setSelected(true);
        return venueRepository.save(oldest);
    }

    /** Manager видит данные своего owner'а */
    private User resolveOwner(User user) {
        // В текущей схеме manager привязан к owner через InviteCode.
        // Если у вас есть поле owner в User — верните его. Пока возвращаем самого user.
        return user;
    }

    private void requireOwner(User user) {
        if (user.getRole() != User.Role.OWNER) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
    }

    private Venue findVenueOwnedBy(User user, UUID venueId) {
        return venueRepository.findByIdAndDeletedAtIsNull(venueId)
                .filter(v -> v.getOwner().getId().equals(user.getId()))
                .orElseThrow(() -> new AppException("VENUE_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private Tables findTableOwnedBy(User user, UUID tableId) {
        Tables table = tableRepository.findByIdAndDeletedAtIsNull(tableId)
                .orElseThrow(() -> new AppException("TABLE_NOT_FOUND", HttpStatus.NOT_FOUND));
        // Проверяем, что venue этого стола принадлежит user'у
        if (!table.getVenue().getOwner().getId().equals(user.getId())) {
            throw new AppException("FORBIDDEN", HttpStatus.FORBIDDEN);
        }
        return table;
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception e) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void validateVenueRequest(VenueRequest r) {
        if (r.getName() == null || r.getName().isBlank() || r.getName().length() > 100) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (r.getNumber() == null || r.getNumber() < 1) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (r.getAddress() != null && r.getAddress().length() > 255) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void validateTableRequest(TableRequest r) {
        if (r.getVenueId() == null) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validateTableUpdateRequest(r);
    }

    private void validateTableUpdateRequest(TableRequest r) {
        if (r.getNumber() == null || r.getNumber() < 1) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (r.getTarifAmount() == null || r.getTarifAmount() < 1 || r.getTarifAmount() > 1_000_000) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (r.getCurrency() == null || r.getTarifType() == null) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (r.getName() != null && r.getName().length() > 100) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (r.getDescription() != null && r.getDescription().length() > 500) {
            throw new AppException("VALIDATION_ERROR", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
}