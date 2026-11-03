export type SuggestionIcon = "search" | "calendar" | "cancel" | "list";

export interface Suggestion {
	icon: SuggestionIcon;
	label: string;
	prompt: string;
}

export const SUGGESTIONS: Suggestion[] = [
	{
		icon: "search",
		label: "Find a room for 10 people at 2:00 PM tomorrow",
		prompt: "Find a free room for 10 people at 2:00 PM tomorrow.",
	},
	{
		icon: "calendar",
		label: "Book room id=1 at 9:00 AM tomorrow",
		prompt:
			"Book room id=1 from 9:00 AM to 10:00 AM tomorrow, title Sprint meeting, organizer an@example.com.",
	},
	{
		icon: "cancel",
		label: "Cancel meeting #1",
		prompt: "Cancel meeting id=1.",
	},
	{
		icon: "list",
		label: "Find a room for 20 people on Floor 3",
		prompt: "Find a free room on Floor 3 for 20 people at 2:00 PM tomorrow.",
	},
];
