package kg.sportmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SportManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SportManagerApplication.class, args);
    }

}
