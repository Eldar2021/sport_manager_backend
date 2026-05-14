package kg.sportmanager.service;

import kg.sportmanager.dto.response.ManagerResponse;
import kg.sportmanager.entity.User;

import java.util.List;

public interface ManagerService {

    List<ManagerResponse> listByOwner(User owner);

    void delete(User owner, String managerId);
}
