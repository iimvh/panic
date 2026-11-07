package com.panic.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a finished trace says about a failure: the step a run died in must be closed with the
 * error (✗ + reason), never left "running" — that is how the UI and the persisted replay show
 * at which step a task failed.
 */
class PipelineRecorderTest {

	private PipelineRecorder recorder;

	@BeforeEach
	void setUp() {
		recorder = new PipelineRecorder(new PipelineBroadcaster());
	}

	@Test
	void failedRunClosesEveryRunningStepWithTheError() {
		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		recorder.startResponse(trace);
		PipelineEvent inFlight = recorder.start(trace, PipelineEvent.PHASE_TOOL, "coordinator",
				"Room search", "{\"query\":\"big room\"}");
		recorder.start(trace, PipelineEvent.PHASE_LLM, "coordinator", "Synthesizing", "{}");

		PipelineEvent[] events = recorder.end(trace, true, "connection to the model timed out");

		assertThat(events).filteredOn(event -> event.phase().equals(PipelineEvent.PHASE_TOOL))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.id()).isEqualTo(inFlight.id());
					assertThat(event.status()).isEqualTo(PipelineEvent.STATUS_ERROR);
					assertThat(event.detail()).isEqualTo("connection to the model timed out");
					assertThat(event.durationMs()).isNotNull();
				});
		assertThat(events).allSatisfy(event ->
				assertThat(event.status()).isNotEqualTo(PipelineEvent.STATUS_RUNNING));
	}

	@Test
	void successfulRunKeepsClosedStatusesUntouched() {
		PipelineRecorder.RequestTrace trace = new PipelineRecorder.RequestTrace();
		recorder.startResponse(trace);
		recorder.start(trace, PipelineEvent.PHASE_LLM, "coordinator", "Model call", "{}");

		PipelineEvent[] events = recorder.end(trace, false, null);

		// No sweep on success: whatever the steps' real statuses are, they stay exactly so.
		assertThat(events).filteredOn(event -> event.phase().equals(PipelineEvent.PHASE_LLM))
				.singleElement()
				.satisfies(event -> assertThat(event.status()).isEqualTo(PipelineEvent.STATUS_RUNNING));
	}
}
