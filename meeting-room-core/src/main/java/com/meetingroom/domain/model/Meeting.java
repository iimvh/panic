package com.meetingroom.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "meetings")
@Getter
@Setter
public class Meeting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(optional = false)
	@JoinColumn(name = "room_id")
	private MeetingRoom room;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String organizer;

	@ElementCollection
	@Column(name = "attendee")
	private List<String> attendees = new ArrayList<>();

	@Column(nullable = false)
	private LocalDateTime startTime;

	@Column(nullable = false)
	private LocalDateTime endTime;

	@Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(nullable = false)
	private MeetingStatus status = MeetingStatus.CONFIRMED;

	private LocalDateTime createdAt = LocalDateTime.now();
}
