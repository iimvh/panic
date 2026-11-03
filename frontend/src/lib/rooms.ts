import { askAgent } from "./api";

export interface Room {
	id: string;
	name: string;
	capacity: number | null;
	location: string | null;
}

const ROOM_LINE =
	/^\s*[-*]\s*id=(\S+)\s*\|\s*name=(.*?)\s*\|\s*capacity=(\S+)\s*\|\s*location=(.*)$/;

const LIST_PROMPT = "List all available meeting rooms.";

export function parseRooms(text: string): Room[] {
	const rooms: Room[] = [];
	for (const line of text.split("\n")) {
		const match = line.match(ROOM_LINE);
		if (!match) continue;
		const capacity = match[3] === "null" ? null : Number.parseInt(match[3], 10);
		rooms.push({
			id: match[1],
			name: match[2] || `Room #${match[1]}`,
			capacity: Number.isNaN(capacity) ? null : capacity,
			location: match[4] === "null" ? null : match[4].trim() || null,
		});
	}
	return rooms;
}

export interface RoomsResult {
	rooms: Room[];
	raw: string;
}

export async function fetchRoomsViaAgent(): Promise<RoomsResult> {
	const { reply } = await askAgent(LIST_PROMPT);
	return { rooms: parseRooms(reply), raw: reply };
}

export function roomSearchPrompt(room: Room): string {
	const parts = ["Find a free room"];
	if (room.location) parts.push(`on ${room.location}`);
	if (room.capacity != null) parts.push(`for ${room.capacity} people`);
	parts.push("at 2:00 PM tomorrow");
	return `${parts.join(" ")}.`;
}

export function bookRoomPrompt(room: Room): string {
	const capacityNote =
		room.capacity != null ? ` (capacity ${room.capacity})` : "";
	return `Book room id=${room.id}${capacityNote} from 9:00 AM to 10:00 AM tomorrow, title Sync meeting, organizer an@example.com.`;
}
