---
name: booking-policy
description: Company rules for choosing, booking and cancelling meeting rooms
mcpServers: []
---
# Meeting room booking policy

1. Only choose rooms that are free for exactly the time slot the user asks for; never infer a different slot.
2. Working hours: 08:00-18:00, Monday to Friday. Ask the user again for any request outside this window.
3. Booking requires all of: roomId, title, organizer, start time and end time.
   - Missing roomId: use the room search agent to look up free rooms first; never invent a roomId.
   - Missing title/organizer: ask the user a short follow-up question.
4. Never invent data: every roomId and meetingId returned to the user must come from tool results.
5. Cancelling: the meetingId must be known; if not, ask the user again.
6. Three active sample rooms: Sala A - Floor 5 (10 seats), Sala B - Floor 5 (4 seats),
   Sala C - Floor 3 (20 seats).
