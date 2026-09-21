package com.bike.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class BikeServerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && com.bike.server.aftersales.AfterSalesVideoWorker.ARGUMENT.equals(args[0])) {
            com.bike.server.aftersales.AfterSalesVideoWorker.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(BikeServerApplication.class, args);
    }
}
