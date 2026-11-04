import type { Suggestion } from "../lib/suggestions";
import { SearchIcon, CalendarIcon, CancelIcon, ListIcon } from "./Icons";

const ICONS = {
	search: SearchIcon,
	calendar: CalendarIcon,
	cancel: CancelIcon,
	list: ListIcon,
} as const;

interface WelcomeProps {
	suggestions: Suggestion[];
	onSuggestion: (suggestion: Suggestion) => void;
}

export default function Welcome({ suggestions, onSuggestion }: WelcomeProps) {
	return (
		<div className="mx-auto w-full max-w-2xl animate-rise pt-6 sm:pt-12">
			<p className="eyebrow text-muted">Assistant</p>
			<h3 className="mt-3 font-sans text-[30px] font-semibold leading-[1.15] tracking-tight text-ink sm:text-[36px]">
				Hello, how can I help?
			</h3>
			<p className="mt-3 max-w-md font-sans text-[16px] leading-[1.5] text-body-muted">
				Ask anything in natural language. Pick a suggestion below or just type your request.
			</p>

			<div className="mt-8 border-t border-hairline">
				{suggestions.map((suggestion) => {
					const Icon = ICONS[suggestion.icon];
					return (
						<button
							key={suggestion.label}
							type="button"
							onClick={() => onSuggestion(suggestion)}
							className="group flex w-full items-center gap-3 border-b border-hairline px-1 py-4 text-left transition-colors hover:bg-surface-alt"
						>
							<span className="grid h-8 w-8 shrink-0 place-items-center rounded-sm border border-hairline bg-card text-body-muted transition-colors group-hover:text-accent">
								<Icon className="h-4 w-4" />
							</span>
							<span className="min-w-0 flex-1 font-sans text-[15px] font-medium leading-[1.4] text-ink">
								{suggestion.label}
							</span>
							<span
								className="font-sans text-[15px] text-muted-soft opacity-0 transition-opacity group-hover:opacity-100"
								aria-hidden="true"
							>
								→
							</span>
						</button>
					);
				})}
			</div>
		</div>
	);
}
