package kg.sportmanager.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Item shape для {@code GET /api/v1/venue/{venueId}/tables} — lightweight,
 * без session/snapshot. Mobile bunu hem listede hem table-edit ekranında kullanır.
 *
 * <p>{@code TableResponse}'tan farkı: {@code session} alanı yok ve zaman alanları
 * ISO 8601 olarak {@link Instant} serialize edilir (string concat değil).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TableListItemResponse {
    private String id;
    private String venueId;
    private int number;
    private String name;          // nullable
    private String description;   // nullable
    private int tarifAmount;
    private String currency;      // KGS | USD | RUB | KZT | TRY
    private String tarifType;     // MINUTE | HOUR | DAY
    private Instant createdAt;
    private Instant updatedAt;
}
