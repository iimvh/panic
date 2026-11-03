package com.meetingroom.domain.repository;

import com.meetingroom.domain.model.Meeting;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

	@Query("""
		SELECT m FROM Meeting m
		WHERE m.room.id = :roomId
		  AND m.status <> 'CANCELLED'
		  AND m.startTime < :endTime
		  AND m.endTime > :startTime
		""")
	List<Meeting> findOverlapping(
		@Param("roomId") Long roomId,
		@Param("startTime") LocalDateTime startTime,
		@Param("endTime") LocalDateTime endTime);
}
