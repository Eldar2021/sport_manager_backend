package kg.sportmanager.controller;

import kg.sportmanager.dto.request.SelectVenueRequest;
import kg.sportmanager.dto.request.TableRequest;
import kg.sportmanager.dto.request.VenueRequest;
import kg.sportmanager.dto.response.*;
import kg.sportmanager.entity.User;
import kg.sportmanager.service.HomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HomePageController {

    private final HomeService homeService;

    @GetMapping("/venue/list")
    public ResponseEntity<List<VenueResponse>> getVenueList(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(homeService.getVenueList(user));
    }

    @GetMapping("/venue/selected")
    public ResponseEntity<SelectedVenueResponse> getSelectedVenue(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(homeService.getSelectedVenue(user));
    }

    @PatchMapping("/venue/selected")
    public ResponseEntity<SelectedVenueResponse> updateSelectedVenue(
            @AuthenticationPrincipal User user,
            @RequestBody SelectVenueRequest request) {
        return ResponseEntity.ok(homeService.updateSelectedVenue(user, request));
    }

    @PostMapping("/venue/create")
    public ResponseEntity<VenueResponse> createVenue(
            @AuthenticationPrincipal User user,
            @RequestBody VenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(homeService.createVenue(user, request));
    }

    @PutMapping("/venue/{id}")
    public ResponseEntity<VenueResponse> updateVenue(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @RequestBody VenueRequest request) {
        return ResponseEntity.ok(homeService.updateVenue(user, id, request));
    }

    @DeleteMapping("/venue/{id}")
    public ResponseEntity<DeleteResponse> deleteVenue(
            @AuthenticationPrincipal User user,
            @PathVariable String id) {
        return ResponseEntity.ok(homeService.deleteVenue(user, id));
    }


    @PostMapping("/table/create")
    public ResponseEntity<TableResponse> createTable(
            @AuthenticationPrincipal User user,
            @RequestBody TableRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(homeService.createTable(user, request));
    }

    @PutMapping("/table/{id}")
    public ResponseEntity<TableResponse> updateTable(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @RequestBody TableRequest request) {
        return ResponseEntity.ok(homeService.updateTable(user, id, request));
    }

    @DeleteMapping("/table/{id}")
    public ResponseEntity<DeleteResponse> deleteTable(
            @AuthenticationPrincipal User user,
            @PathVariable String id) {
        return ResponseEntity.ok(homeService.deleteTable(user, id));
    }
}