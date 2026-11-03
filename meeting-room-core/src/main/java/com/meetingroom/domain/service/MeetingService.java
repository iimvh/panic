package com.meetingroom.domain.service;

import com.meetingroom.domain.model.BookingRequest;
import com.meetingroom.domain.model.Meeting;
import com.meetingroom.domain.model.MeetingStatus;
import com.meetingroom.domain.model.MeetingRoom;
import com.meetingroom.domain.repository.MeetingRepository;
import com.meetingroom.domain.repository.MeetingRoomRepository;
import com.meetingroom.exception.BookingException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {

	private final MeetingRoomRepository roomRepository;
	private final MeetingRepository meetingRepository;

	public MeetingService(MeetingRoomRepository roomRepository, MeetingRepository meetingRepository) {
		this.roomRepository = roomRepository;
		this.meetingRepository = meetingRepository;
	}

	@Transactional
	public Meeting book(BookingRequest request) {
		if (request == null) {
			throw new BookingException("Missing booking details");
		}
		if (request.roomId() == null) {
			throw new BookingException("Please provide the room (roomId) to book");
		}
		if (request.title() == null || request.title().isBlank()) {
			throw new BookingException("Please provide the meeting title");
		}
		if (request.organizer() == null || request.organizer().isBlank()) {
			throw new BookingException("Please provide the meeting organizer");
		}
		if (request.startTime() == null || request.endTime() == null) {
			throw new BookingException("Please provide the start and end time");
		}
		MeetingRoom room = roomRepository.findById(request.roomId())
			.orElseThrow(() -> new BookingException("Meeting room does not exist: " + request.roomId()));
		if (room.getActive() == null || !room.getActive()) {
			throw new BookingException("Meeting room is currently inactive");
		}
		if (request.startTime().isBefore(LocalDateTime.now())) {
			throw new BookingException("Start time must be now or in the future");
		}
		if (!request.endTime().isAfter(request.startTime())) {
			throw new BookingException("End time must be after the start time");
		}

		List<Meeting> overlaps = meetingRepository.findOverlapping(room.getId(), request.startTime(), request.endTime());
		if (!overlaps.isEmpty()) {
			throw new BookingException("The room is already booked for this time slot");
		}

		Meeting meeting = new Meeting();
		meeting.setRoom(room);
		meeting.setTitle(request.title());
		meeting.setOrganizer(request.organizer());
		meeting.setAttendees(request.attendees() != null ? request.attendees() : List.of());
		meeting.setStartTime(request.startTime());
		meeting.setEndTime(request.endTime());
		meeting.setStatus(MeetingStatus.CONFIRMED);
		return meetingRepository.save(meeting);
	}

	@Transactional
	public void cancel(Long meetingId) {
		Meeting meeting = meetingRepository.findById(meetingId)
			.orElseThrow(() -> new BookingException("Meeting does not exist: " + meetingId));
		meeting.setStatus(MeetingStatus.CANCELLED);
		meetingRepository.save(meeting);
	}

	@Transactional(readOnly = true)
	public Meeting get(Long meetingId) {
		return meetingRepository.findById(meetingId)
			.orElseThrow(() -> new BookingException("Meeting does not exist: " + meetingId));
	}

	public Integer capacityOf(Long roomId) {
		return roomRepository.findById(roomId).map(MeetingRoom::getCapacity).orElse(null);
	}
}
