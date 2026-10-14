package dev.zhiku;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
@SpringBootApplication
@ConfigurationPropertiesScan
public class ZhikuApplication {
 public static void main(String[] args) { SpringApplication.run(ZhikuApplication.class, args); }
}
