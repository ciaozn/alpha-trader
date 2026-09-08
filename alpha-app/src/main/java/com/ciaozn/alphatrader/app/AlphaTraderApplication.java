package com.ciaozn.alphatrader.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlphaTraderApplication {

    private static final Logger log = LoggerFactory.getLogger(AlphaTraderApplication.class);

    public static void main(String[] args) {
        var context = SpringApplication.run(AlphaTraderApplication.class, args);
        Environment env = context.getEnvironment();
        log.info("""

                ================================================
                 Alpha Trader started
                 profiles : {}
                 mode     : {}
                ================================================
                """,
                Arrays.toString(env.getActiveProfiles()),
                env.getProperty("alpha.mode", "undefined"));
    }
}
