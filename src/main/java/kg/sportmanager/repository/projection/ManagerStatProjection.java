package kg.sportmanager.repository.projection;

public interface ManagerStatProjection {
    String getManagerId();
    String getManagerName();
    String getUsername();   // алиас "username" → Spring Data маппит на getUsername()
    long getRevenue();
    long getSessions();
    long getCancelCount();
}