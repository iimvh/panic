package com.meetingroom.domain.model;

import java.time.LocalDateTime;

public record RoomSearchCriteria(
	LocalDateTime startTime,
	LocalDateTime endTime,
	Integer capacity,
	String location
) {
}
