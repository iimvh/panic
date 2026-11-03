package com.meetingroom.domain.repository;

import com.meetingroom.domain.model.MeetingRoom;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRoomRepository extends JpaRepository<MeetingRoom, Long> {

	List<MeetingRoom> findByActiveTrueAndCapacityGreaterThanEqual(Integer capacity);

	@Query("""
		SELECT r FROM MeetingRoom r
		WHERE r.active = true
		  AND (:capacity IS NULL OR r.capacity >= :capacity)
		  AND (:location IS NULL OR r.location = :location)
		  AND NOT EXISTS (
		    SELECT m FROM Meeting m
		    WHERE m.room = r
		      AND m.status <> 'CANCELLED'
		      AND m.startTime < :endTime
		      AND m.endTime > :startTime)
		""")
	List<MeetingRoom> findAvailable(
		@Param("startTime") LocalDateTime startTime,
		@Param("endTime") LocalDateTime endTime,
		@Param("capacity") Integer capacity,
		@Param("location") String location);
}
