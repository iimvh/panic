import type { Suggestion } from "../lib/suggestions";
import { SparkIcon } from "./Icons";

interface WelcomeProps {
	suggestions: Suggestion[];
	onSuggestion: (suggestion: Suggestion) => void;
}

export default function Welcome({ suggestions, onSuggestion }: WelcomeProps) {
	return (
		<div className="mx-auto flex w-full max-w-2xl animate-rise flex-col items-center pt-6 text-center sm:pt-14">
			<div className="grid h-14 w-14 place-items-center rounded-2xl bg-gradient-to-br from-accent-500 to-violet-600 text-white shadow-glow">
				<SparkIcon className="h-7 w-7" />
			</div>
			<h3 className="mt-5 text-[26px] font-extrabold tracking-tight text-slate-900 sm:text-3xl">
				Hello, how can I help?
			</h3>
			<p className="mt-2 max-w-md text-[13.5px] leading-relaxed text-slate-500">
				I can find free rooms, book meetings and cancel meetings for you. Pick a suggestion below or type a
				request in natural language.
			</p>

			<div className="mt-7 grid w-full gap-2.5 sm:grid-cols-2">
				{suggestions.map((suggestion) => (
					<button
						key={suggestion.label}
						type="button"
						onClick={() => onSuggestion(suggestion)}
						className="group flex items-start gap-3 rounded-2xl border border-slate-200/90 bg-white p-3.5 text-left transition hover:-translate-y-0.5 hover:border-accent-300 hover:shadow-lg hover:shadow-accent-500/10"
					>
						<span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-lg bg-accent-50 text-accent-600 transition group-hover:bg-accent-100">
							<SparkIcon className="h-3.5 w-3.5" />
						</span>
						<span className="text-[12.8px] leading-snug text-slate-600 group-hover:text-slate-900">
							{suggestion.label}
						</span>
					</button>
				))}
			</div>
		</div>
	);
}
