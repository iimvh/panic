package com.meetingroom.domain.model;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.List;

public record BookingRequest(
	@NotNull Long roomId,
	@NotBlank String title,
	@NotBlank String organizer,
	List<String> attendees,
	@NotNull @FutureOrPresent LocalDateTime startTime,
	@NotNull LocalDateTime endTime
) {
}
