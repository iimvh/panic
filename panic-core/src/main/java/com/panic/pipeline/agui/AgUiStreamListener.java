package com.panic.pipeline.agui;

import com.panic.pipeline.PipelineEvent;

/**
 * Realtime listener for one agent run. The recorder/broadcaster push domain bearings here;
 * the implementing stream translates them into AG-UI wire events.
 */
public interface AgUiStreamListener {

	/** One recorded pipeline step, as it happens. */
	void pipelineStep(PipelineEvent event);

	/** One streamed chunk of the assistant's answer, as the model produces it. */
	void answerDelta(String text, String agentId);
}
