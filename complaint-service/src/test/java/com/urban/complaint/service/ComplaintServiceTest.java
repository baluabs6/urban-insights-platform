package com.urban.complaint.service;

import com.urban.complaint.dto.ComplaintRequest;
import com.urban.complaint.entity.CitizenComplaint;
import com.urban.complaint.repository.ComplaintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComplaintServiceTest {

    @Mock
    private ComplaintRepository repository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ComplaintService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ComplaintService(repository, kafkaTemplate);
    }

    @Test
    void heuristicCategory_usesSuppliedCategoryWhenPresent() {
        assertThat(service.heuristicCategory("some text", "GARBAGE")).isEqualTo("GARBAGE");
    }

    @Test
    void heuristicCategory_detectsPotholeFromDescription() {
        assertThat(service.heuristicCategory("Big pothole on the road", null)).isEqualTo("POTHOLE");
    }

    @Test
    void heuristicCategory_detectsStreetlightFromDescription() {
        assertThat(service.heuristicCategory("streetlight is broken", "")).isEqualTo("STREETLIGHT");
    }

    @Test
    void heuristicCategory_fallsBackToOtherWhenNoKeywordMatches() {
        assertThat(service.heuristicCategory("something unusual is happening", null)).isEqualTo("OTHER");
    }

    @Test
    void heuristicUrgency_highForDangerKeywords() {
        assertThat(service.heuristicUrgency("this is a dangerous accident waiting to happen")).isEqualTo(0.9);
    }

    @Test
    void heuristicUrgency_mediumForOverflowKeyword() {
        assertThat(service.heuristicUrgency("bin is overflowing")).isEqualTo(0.6);
    }

    @Test
    void heuristicUrgency_lowByDefault() {
        assertThat(service.heuristicUrgency("minor cosmetic issue")).isEqualTo(0.3);
    }

    @Test
    void submit_withIdempotencyKey_returnsExistingComplaintInsteadOfCreatingNew() {
        String key = "idem-key-123";
        CitizenComplaint existing = CitizenComplaint.builder().id("existing-id").idempotencyKey(key).build();
        when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        ComplaintRequest request = new ComplaintRequest();
        request.setIdempotencyKey(key);
        request.setCitizenId("C1");
        request.setCategory("POTHOLE");
        request.setDescription("desc");
        request.setZone("Whitefield");
        request.setLatitude(12.9);
        request.setLongitude(77.6);

        CitizenComplaint result = service.submit(request);

        assertThat(result.getId()).isEqualTo("existing-id");
        verify(repository, never()).save(any());
        verifyNoInteractions(kafkaTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    void submit_withoutIdempotencyKey_alwaysCreatesNewComplaint() {
        when(repository.save(any())).thenAnswer(invocation -> {
            CitizenComplaint c = invocation.getArgument(0);
            c.setId("new-id");
            return c;
        });
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        ComplaintRequest request = new ComplaintRequest();
        request.setCitizenId("C1");
        request.setCategory("GARBAGE");
        request.setDescription("garbage everywhere");
        request.setZone("Andheri");
        request.setLatitude(19.1);
        request.setLongitude(72.8);

        CitizenComplaint result = service.submit(request);

        assertThat(result.getId()).isEqualTo("new-id");
        assertThat(result.getClassificationSource()).isEqualTo("PENDING_AI");
        assertThat(result.getStatus()).isEqualTo("OPEN");
        assertThat(result.getHistory()).hasSize(1);
        verify(repository, never()).findByIdempotencyKey(any());
    }
}
