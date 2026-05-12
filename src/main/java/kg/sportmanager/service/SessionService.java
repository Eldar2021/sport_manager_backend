package kg.sportmanager.service;

import kg.sportmanager.dto.request.CancelSessionRequest;
import kg.sportmanager.dto.request.FinishSessionRequest;
import kg.sportmanager.dto.request.StartSessionRequest;
import kg.sportmanager.dto.response.SessionLiteResponse;
import kg.sportmanager.dto.response.SessionResultResponse;
import kg.sportmanager.entity.User;

public interface SessionService {

    /** Начать сессию на столе. */
    SessionLiteResponse start(User user, StartSessionRequest request);

    /** Поставить сессию на паузу. */
    SessionLiteResponse pause(User user, String sessionId);

    /** Снять сессию с паузы. */
    SessionLiteResponse resume(User user, String sessionId);

    /** Завершить сессию и принять оплату. */
    SessionResultResponse finish(User user, String sessionId, FinishSessionRequest request);

    /** Отменить сессию (случайный старт). */
    SessionResultResponse cancel(User user, String sessionId, CancelSessionRequest request);
}