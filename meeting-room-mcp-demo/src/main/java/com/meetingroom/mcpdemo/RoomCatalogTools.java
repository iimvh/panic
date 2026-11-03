package com.meetingroom.mcpdemo;

import java.util.List;
import java.util.Optional;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Static room catalog for the MCP demo. The data is independent of the app's H2 database to prove
 * that the {@code roomCatalog} agent can only obtain its data through MCP.
 */
@Component
public class RoomCatalogTools {

	public record Room(long id, String name, int capacity, String location, List<String> amenities) {
	}

	private static final List<Room> ROOMS = List.of(
			new Room(101, "Sala A - Floor 5", 10, "Floor 5", List.of("Projector", "Whiteboard")),
			new Room(102, "Sala B - Floor 5", 4, "Floor 5", List.of("50-inch TV")),
			new Room(103, "Sala C - Floor 3", 20, "Floor 3", List.of("Projector", "Video conferencing")),
			new Room(104, "Focus Room - Floor 2", 2, "Floor 2", List.of("Dual desk", "Soundproofing")),
			new Room(105, "Auditorium - Floor 1", 50, "Floor 1", List.of("Stage", "Audio", "Lighting")));

	@Tool(name = "listRooms",
			description = "List every meeting room in the company catalog (id, name, capacity, location).")
	public List<Room> listRooms() {
		return ROOMS;
	}

	@Tool(name = "roomDetail", description = "Show details of one meeting room by catalog id.")
	public String roomDetail(@ToolParam(description = "room id, e.g. 101") long roomId) {
		Optional<Room> room = ROOMS.stream().filter(r -> r.id() == roomId).findFirst();
		return room.map(r -> "id=" + r.id() + " | name=" + r.name() + " | capacity=" + r.capacity()
				+ " | location=" + r.location() + " | amenities=" + String.join(", ", r.amenities()))
				.orElse("No room with id=" + roomId);
	}
}
