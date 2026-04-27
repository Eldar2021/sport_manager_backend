package kg.sportmanager.service;

import kg.sportmanager.dto.request.SelectVenueRequest;
import kg.sportmanager.dto.request.TableRequest;
import kg.sportmanager.dto.request.VenueRequest;
import kg.sportmanager.dto.response.*;
import kg.sportmanager.entity.User;

import java.util.List;

public interface HomeService {

    List<VenueResponse> getVenueList(User user);

    SelectedVenueResponse getSelectedVenue(User user);

    SelectedVenueResponse updateSelectedVenue(User user, SelectVenueRequest request);

    VenueResponse createVenue(User user, VenueRequest request);

    VenueResponse updateVenue(User user, String venueId, VenueRequest request);

    DeleteResponse deleteVenue(User user, String venueId);

    TableResponse createTable(User user, TableRequest request);

    TableResponse updateTable(User user, String tableId, TableRequest request);

    DeleteResponse deleteTable(User user, String tableId);
}