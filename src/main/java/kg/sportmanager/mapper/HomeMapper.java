package kg.sportmanager.mapper;

import kg.sportmanager.dto.response.SessionResponse;
import kg.sportmanager.dto.response.TableResponse;
import kg.sportmanager.dto.response.VenueResponse;
import kg.sportmanager.entity.Session;
import kg.sportmanager.entity.Tables;
import kg.sportmanager.entity.Venue;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HomeMapper {

    public VenueResponse toVenueResponse(Venue venue, int tableCount) {
        return VenueResponse.builder()
                .id(venue.getId().toString())
                .name(venue.getName())
                .number(venue.getNumber())
                .address(venue.getAddress())
                .selected(venue.isSelected())
                .tableCount(tableCount)
                .createdAt(venue.getCreatedAt().toString())
                .updatedAt(venue.getUpdatedAt().toString())
                .build();
    }

    public TableResponse toTableResponse(Tables table, Session session) {
        return TableResponse.builder()
                .id(table.getId().toString())
                .venueId(table.getVenue().getId().toString())
                .name(table.getName())
                .number(table.getNumber())
                .description(table.getDescription())
                .tarifAmount(table.getTarifAmount())
                .currency(table.getCurrency().name())
                .tarifType(table.getTarifType().name())
                .session(session != null ? toSessionResponse(session) : null)
                .createdAt(table.getCreatedAt().toString())
                .updatedAt(table.getUpdatedAt().toString())
                .build();
    }

    public SessionResponse toSessionResponse(Session session) {
        return SessionResponse.builder()
                .id(session.getId().toString())
                .tableId(session.getTable().getId().toString())
                .isActive(session.isActive())
                .isPaused(session.isPaused())
                .startedAt(session.getStartedAt().toString())
                .pausedAt(session.getPausedAt() != null ? session.getPausedAt().toString() : null)
                .resumedAt(session.getResumedAt() != null ? session.getResumedAt().toString() : null)
                .totalPausedSeconds(session.getTotalPausedSeconds())
                .tarifAmountSnapshot(session.getTarifAmountSnapshot())
                .tarifTypeSnapshot(session.getTarifTypeSnapshot().name())
                .build();
    }

    public List<TableResponse> toTableResponseList(List<Tables> tables,
                                                   java.util.function.Function<Tables, Session> sessionFetcher) {
        return tables.stream()
                .map(t -> toTableResponse(t, sessionFetcher.apply(t)))
                .toList();
    }
}