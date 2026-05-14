package kg.sportmanager.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kg.sportmanager.entity.Tables;
import lombok.Data;

@Data
public class TableRequest {
    /** Только для create. На update игнорируется (стол не переезжает). */
    private String venueId;

    @Size(max = 100)
    private String name;

    @NotNull
    @Min(1)
    private Integer number;

    @Size(max = 500)
    private String description;

    @NotNull
    @Min(1)
    @Max(1_000_000)
    private Integer tarifAmount;

    @NotNull
    private Tables.Currency currency;

    @NotNull
    private Tables.TarifType tarifType;
}