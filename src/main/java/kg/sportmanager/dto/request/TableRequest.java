package kg.sportmanager.dto.request;

import kg.sportmanager.entity.Tables;
import lombok.Data;

@Data
public class TableRequest {
    private String venueId; // только для create
    private String name;
    private Integer number;
    private String description;
    private Integer tarifAmount;
    private Tables.Currency currency;
    private Tables.TarifType tarifType;
}