package com.urban.traffic.service;

import com.urban.traffic.dto.SensorReadingRequest;
import com.urban.traffic.entity.TrafficSensorReading;
import com.urban.traffic.repository.TrafficSensorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class TrafficIngestionServiceTest {

    @Mock
    private TrafficSensorRepository repository;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private ListOperations<String, Object> listOperations;

    private TrafficIngestionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new TrafficIngestionService(repository, redisTemplate);
    }

    private SensorReadingRequest request(double value) {
        SensorReadingRequest r = new SensorReadingRequest();
        r.setSensorId("AQI-TEST-01");
        r.setSensorType("AQI");
        r.setZone("Whitefield");
        r.setLatitude(12.97);
        r.setLongitude(77.75);
        r.setValue(value);
        r.setUnit("AQI");
        return r;
    }

    private List<Object> baselineWindow(double mean, double spread, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> String.valueOf(mean + (i % 2 == 0 ? spread : -spread)))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Test
    void ingest_flagsAnomaly_whenValueFarExceedsRollingWindowBaseline() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(baselineWindow(100.0, 1.0, 20));

        TrafficSensorReading result = service.ingest(request(500));

        assertThat(result.getAnomaly()).isTrue();
        assertThat(result.getAnomalyScore()).isGreaterThan(3.0);
    }

    @Test
    void ingest_doesNotFlagAnomaly_whenValueIsWithinNormalRange() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(baselineWindow(100.0, 5.0, 20));

        TrafficSensorReading result = service.ingest(request(103));

        assertThat(result.getAnomaly()).isFalse();
    }

    @Test
    void ingest_doesNotFlagAnomaly_whenBaselineHasTooFewSamples() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(baselineWindow(100.0, 1.0, 3));

        TrafficSensorReading result = service.ingest(request(9999));

        assertThat(result.getAnomaly()).isFalse();
        assertThat(result.getAnomalyScore()).isNull();
    }

    @Test
    void ingest_doesNotFlagAnomaly_whenWindowIsEmpty() {
        when(listOperations.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);

        TrafficSensorReading result = service.ingest(request(250));

        assertThat(result.getAnomaly()).isFalse();
    }
}
