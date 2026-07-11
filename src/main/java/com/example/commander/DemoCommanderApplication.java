package com.example.commander;

import com.example.commander.batch.config.BatchPipelineProperties;
import com.example.commander.config.ReadLayerProperties;
import com.example.commander.config.SchedulingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SchedulingProperties.class, ReadLayerProperties.class, BatchPipelineProperties.class})
public class DemoCommanderApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoCommanderApplication.class, args);
    }
}
