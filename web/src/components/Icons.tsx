import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

function base(props: IconProps) {
	return {
		viewBox: "0 0 24 24",
		fill: "none",
		stroke: "currentColor",
		strokeWidth: 2,
		strokeLinecap: "round" as const,
		strokeLinejoin: "round" as const,
		"aria-hidden": true,
		...props,
	};
}

export const SearchIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<circle cx="11" cy="11" r="7" />
		<path d="m20 20-3.5-3.5" />
	</svg>
);

export const CalendarIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<rect x="3" y="4" width="18" height="17" rx="2" />
		<path d="M16 2v4M8 2v4M3 10h18" />
		<path d="M12 14v4M10 16h4" />
	</svg>
);

export const CancelIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<circle cx="12" cy="12" r="9" />
		<path d="m15 9-6 6M9 9l6 6" />
	</svg>
);

export const ListIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M8 6h13M8 12h13M8 18h13" />
		<circle cx="3.5" cy="6" r="1" fill="currentColor" stroke="none" />
		<circle cx="3.5" cy="12" r="1" fill="currentColor" stroke="none" />
		<circle cx="3.5" cy="18" r="1" fill="currentColor" stroke="none" />
	</svg>
);

export const SendIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="m22 2-7 20-4-9-9-4Z" />
		<path d="M22 2 11 13" />
	</svg>
);

export const MenuIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M4 7h16M4 12h16M4 17h16" />
	</svg>
);

export const CloseIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M18 6 6 18M6 6l12 12" />
	</svg>
);

export const RefreshIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M21 12a9 9 0 1 1-2.64-6.36" />
		<path d="M21 3v6h-6" />
	</svg>
);

export const TrashIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M3 6h18" />
		<path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2" />
		<path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
		<path d="M10 11v6M14 11v6" />
	</svg>
);

export const SparkIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8" />
	</svg>
);

export const UsersIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
		<circle cx="9" cy="7" r="4" />
		<path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13A4 4 0 0 1 16 11" />
	</svg>
);

export const PinIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
		<circle cx="12" cy="10" r="3" />
	</svg>
);

export const ArrowIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M5 12h14M13 6l6 6-6 6" />
	</svg>
);

export const PipelineIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<circle cx="5" cy="6" r="2.5" />
		<circle cx="19" cy="12" r="2.5" />
		<circle cx="5" cy="18" r="2.5" />
		<path d="M7.5 7.2 16.6 11M7.5 16.8 16.6 13" />
	</svg>
);

export const ChevronLeftIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="m15 18-6-6 6-6" />
	</svg>
);

export const ErrorIcon = (props: IconProps) => (
	<svg {...base(props)}>
		<path d="M12 8v5M12 16.5v.5" />
		<circle cx="12" cy="12" r="9" />
	</svg>
);
