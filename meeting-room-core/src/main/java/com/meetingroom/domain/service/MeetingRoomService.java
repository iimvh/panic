package com.meetingroom.domain.service;

import com.meetingroom.domain.model.MeetingRoom;
import com.meetingroom.domain.model.RoomSearchCriteria;
import com.meetingroom.domain.repository.MeetingRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingRoomService {

	private final MeetingRoomRepository roomRepository;

	public MeetingRoomService(MeetingRoomRepository roomRepository) {
		this.roomRepository = roomRepository;
	}

	@Transactional(readOnly = true)
	public List<MeetingRoom> searchAvailable(LocalDateTime startTime, LocalDateTime endTime,
			Integer capacity, String location) {
		return roomRepository.findAvailable(startTime, endTime, capacity, location);
	}

	@Transactional(readOnly = true)
	public List<MeetingRoom> searchAvailable(RoomSearchCriteria criteria) {
		return roomRepository.findAvailable(
			criteria.startTime(), criteria.endTime(), criteria.capacity(), criteria.location());
	}
}
