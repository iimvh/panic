export type SuggestionIcon = "search" | "calendar" | "cancel" | "list";

export interface Suggestion {
	icon: SuggestionIcon;
	label: string;
	prompt: string;
}

export const SUGGESTIONS: Suggestion[] = [
	{
		icon: "search",
		label: "Summarize what Panic can do",
		prompt: "Summarize what you can do for me in a few bullet points.",
	},
	{
		icon: "list",
		label: "Draft a short project plan",
		prompt: "Draft a short project plan for launching a new internal tool.",
	},
	{
		icon: "calendar",
		label: "Explain a concept simply",
		prompt: "Explain how retrieval-augmented generation works, simply.",
	},
	{
		icon: "cancel",
		label: "Help me debug a problem",
		prompt: "Help me debug why my web app is slow to load.",
	},
];
