package kg.sportmanager.dto.request;

import lombok.Data;

@Data
public class VenueRequest {
    private String name;
    private Integer number;
    private String address;
}