package com.cobre.challenge.adapter.in.web.local.webhookstub;

import com.cobre.challenge.adapter.in.web.local.webhookstub.dto.RecordedRequest;
import com.cobre.challenge.adapter.in.web.local.webhookstub.behavior.ForcedBehavior;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub webhook receiver for local development and the live demo only.
 * Records what it receives and can be forced to return an arbitrary
 * status code or hang, to exercise timeout/retry paths. Never active
 */
@RestController
@RequestMapping("/local/webhook-stub")
@Profile("local")
public class LocalWebhookStubController {

    private final LocalWebhookStubRecorder recorder;

    public LocalWebhookStubController(LocalWebhookStubRecorder recorder) {
        this.recorder = recorder;
    }

    @RequestMapping(
            path = "/receive",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        recorder.record(RecordedRequest.from(request));

        ForcedBehavior behavior = recorder.currentBehavior();
        int status = HttpStatus.OK.value();
        if (behavior instanceof ForcedBehavior.Hang hang) {
            hang.await();
        } else if (behavior instanceof ForcedBehavior.Status(int code)) {
            status = code;
        }
        return ResponseEntity.status(status).build();
    }

    @GetMapping("/requests")
    public ResponseEntity<List<RecordedRequest>> requests() {
        return ResponseEntity.ok(recorder.recentFirst());
    }

    @DeleteMapping("/requests")
    public ResponseEntity<Void> clearRequests() {
        recorder.clearRecords();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/control/status/{code}")
    public ResponseEntity<Void> forceStatus(@PathVariable int code) {
        recorder.forceStatus(code);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/control/hang")
    public ResponseEntity<Void> forceHang(@RequestParam long millis) {
        recorder.forceHang(Duration.ofMillis(millis));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/control/reset")
    public ResponseEntity<Void> reset() {
        recorder.reset();
        return ResponseEntity.noContent().build();
    }
}
