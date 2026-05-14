package kg.sportmanager.configuration;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

@Configuration
public class MessageConfiguration {

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // Системная locale JVM не должна влиять на fallback: иначе messages_*.properties
        // случайно подменяются переводом активной системной локали (например, ky/ru вместо en).
        source.setFallbackToSystemLocale(false);
        // Базовый бандл messages.properties — английский; используем его как явный default.
        source.setDefaultLocale(java.util.Locale.ENGLISH);
        return source;
    }
}
